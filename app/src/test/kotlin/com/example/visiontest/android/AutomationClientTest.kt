package com.example.visiontest.android

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.performance.NativeTimingEvent
import com.example.visiontest.performance.NativeTimingStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.performance.TraceRuntime
import java.nio.file.Files
import com.example.visiontest.common.elementTapReadTimeoutMs
import com.example.visiontest.config.AutomationConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.SocketTimeoutException
import kotlin.coroutines.Continuation
import kotlin.test.*

class AutomationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AutomationClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AutomationClient(
            host = server.hostName,
            port = server.port
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `native timing is omitted without an active invocation and for health`() = runBlocking {
        val native = mutableListOf<NativeTimingEvent>()
        val trace = TraceRecorder(emit = {}, emitNative = { native.add(it); Unit })
        val traced = AutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody("original"))
        assertEquals("original", traced.sendRequest("device.info"))
        assertEquals(null, server.takeRequest().getHeader("X-VisionTest-Trace"))
        server.enqueue(MockResponse().setBody("original"))
        assertEquals("original", client.sendRequest("device.info"))
        assertEquals(null, server.takeRequest().getHeader("X-VisionTest-Trace"))
        server.enqueue(MockResponse().setBody("ok"))
        assertTrue(trace.invocation("get_device_info", "android") { traced.isServerRunning() })
        val health = server.takeRequest()
        assertEquals("/health", health.path)
        assertEquals(null, health.getHeader("X-VisionTest-Trace"))
        assertTrue(native.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `HTTP errors and connection loss keep their failures and are never replayed`() = runBlocking {
        val native = mutableListOf<NativeTimingEvent>()
        val trace = TraceRecorder(emit = {}, emitNative = { native.add(it); Unit })
        val traced = AutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setResponseCode(503).setHeader("X-VisionTest-Timing", "!").setBody("original"))
        val error = assertFailsWith<CommandExecutionException> {
            trace.invocation("get_device_info", "android") { traced.sendRequest("device.info") }
        }
        assertEquals("HTTP error: 503 - original", error.message)
        assertEquals(NativeTimingStatus.INVALID, native.single().timing.status)
        server.enqueue(MockResponse().setChunkedBody("x".repeat(1024), 64)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertFailsWith<IOException> {
            trace.invocation("get_device_info", "android") { traced.sendRequest("device.info") }
        }
        assertEquals(NativeTimingStatus.MISSING, native.last().timing.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `concurrent duplicate RPC IDs retain separate native request correlation`() = runBlocking {
        val native = Collections.synchronizedList(mutableListOf<NativeTimingEvent>())
        val trace = TraceRecorder(emit = {}, emitNative = { native.add(it); Unit })
        val traced = AutomationClient(server.hostName, server.port, trace)
        val arrivals = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val parts = request.getHeader("X-VisionTest-Trace")!!.split(';')
                arrivals.countDown()
                check(arrivals.await(5, TimeUnit.SECONDS))
                val json = """{"version":1,"invocationId":"${parts[1]}","requestSpanId":"${parts[2]}",""" +
                    """"spans":[{"spanId":"${"c".repeat(32)}","parentSpanId":null,"stage":"request",""" +
                    """"startOffsetNs":0,"durationNs":10,"outcome":"returned","metrics":{"pollCount":2}}],""" +
                    """"missingStages":["ui_sync"]}"""
                val header = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
                return MockResponse().setHeader("X-VisionTest-Timing", header).setBody("original")
            }
        }
        val results = (1..2).map {
            async { trace.invocation("get_device_info", "android") { traced.sendRequest("device.info", id = 7) } }
        }.awaitAll()
        assertEquals(listOf("original", "original"), results)
        assertEquals(2, server.requestCount)
        assertEquals(2, native.map { it.invocationId }.distinct().size)
        assertEquals(2, native.map { it.requestSpanId }.distinct().size)
        assertTrue(native.all { it.timing.status == NativeTimingStatus.AVAILABLE })
        assertTrue(native.all { it.timing.spans.single().durationNs == 10L })
        assertTrue(native.all { it.timing.missingStages.single().wireName == "ui_sync" })
        val sent = (1..2).map { server.takeRequest().getHeader("X-VisionTest-Trace") }
        assertEquals(sent.toSet(), native.map { "v1;${it.invocationId};${it.requestSpanId}" }.toSet())
    }

    @Test
    fun `native sink failures cannot replace operation results`() = runBlocking {
        val trace = TraceRecorder(emit = {}, emitNative = { error("broken sink") })
        val traced = AutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody("original"))
        assertEquals("original", trace.invocation("get_device_info", "android") { traced.sendRequest("device.info") })
        server.enqueue(MockResponse().setResponseCode(503).setBody("original"))
        val error = assertFailsWith<CommandExecutionException> {
            trace.invocation("get_device_info", "android") { traced.sendRequest("device.info") }
        }
        assertEquals("HTTP error: 503 - original", error.message)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `optional native metadata preserves exact RPC bodies with one dispatch`() = runBlocking {
        val path = Files.createTempFile("native-client", ".jsonl")
        try {
            val runtime = TraceRuntime("mcp", System.nanoTime())
            runtime.configure(path)
            val traced = AutomationClient(server.hostName, server.port, runtime.recorder)
            val bodies = listOf("""{"jsonrpc":"2.0","result":{"success":true},"id":7}""",
                """{"jsonrpc":"2.0","error":{"code":-1,"message":"original"},"id":7}""")
            for (body in bodies) {
                for (header in listOf(null, "!")) {
                    val response = MockResponse().setBody(body)
                    header?.let { response.setHeader("X-VisionTest-Timing", it) }
                    server.enqueue(response)
                    assertEquals(body, runtime.recorder.invocation("get_device_info", "android") {
                        traced.sendRequest("device.info", id = 7)
                    })
                    val request = server.takeRequest()
                    assertEquals("/jsonrpc", request.path)
                    assertEquals("""{"jsonrpc":"2.0","method":"device.info","params":{},"id":7}""",
                        request.body.readUtf8())
                    assertTrue(request.getHeader("X-VisionTest-Trace")
                        ?.matches(Regex("v1;[0-9a-f]{32};[0-9a-f]{32}")) == true)
                }
            }
            runtime.finish()
            assertEquals(4, server.requestCount)
            val events = Files.readAllLines(path).map { JsonParser.parseString(it).asJsonObject }
            val native = events.filter { it["type"]?.asString == "native.timing" }
            assertEquals(listOf("missing", "invalid", "missing", "invalid"), native.map { it["status"].asString })
            val exchanges = events.filter { it["stage"]?.asString == "http.exchange" }
            assertEquals(exchanges.map { it["spanId"].asString }.toSet(),
                native.map { it["requestSpanId"].asString }.toSet())
            assertTrue(native.all { it["clockDomain"].asString == "native.request" })
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // --- sendRequest ---

    @Test
    fun `sendRequest posts to jsonrpc endpoint`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("test.method")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/jsonrpc", request.path)
        assertEquals("application/json", request.getHeader("Content-Type"))
    }

    @Test
    fun `sendRequest sends correct JSON-RPC body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("test.method")

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("2.0", body.get("jsonrpc").asString)
        assertEquals("test.method", body.get("method").asString)
        assertEquals(1, body.get("id").asInt)
    }

    @Test
    fun `sendRequest serializes params correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("ui.tap", mapOf("x" to 100, "y" to 200))

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertEquals(100, params.get("x").asInt)
        assertEquals(200, params.get("y").asInt)
    }

    @Test
    fun `sendRequest sends empty params when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("test.method")

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertEquals(0, params.size())
    }

    @Test
    fun `sendRequest returns response body on success`() = runBlocking {
        val responseBody = """{"jsonrpc":"2.0","result":{"hierarchy":"<xml/>"},"id":1}"""
        server.enqueue(MockResponse().setBody(responseBody))

        val result = client.sendRequest("ui.dumpHierarchy")

        assertEquals(responseBody, result)
    }

    @Test
    fun `sendRequest serializes string params correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("ui.inputText", mapOf("text" to "hello world"))

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertEquals("hello world", params.get("text").asString)
    }

    @Test
    fun `sendRequest serializes boolean params correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("ui.getInteractiveElements", mapOf("includeDisabled" to true))

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertTrue(params.get("includeDisabled").asBoolean)
    }

    @Test
    fun `sendRequest uses custom id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":42}"""))

        client.sendRequest("test.method", id = 42)

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(42, body.get("id").asInt)
    }

    @Test
    fun `sendRequest throws CommandExecutionException on non-200 response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val exception = assertFailsWith<CommandExecutionException> {
            client.sendRequest("test.method")
        }
        assertTrue(exception.message!!.contains("500"))
    }

    @Test
    fun `sendRequest throws CommandExecutionException on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        assertFailsWith<CommandExecutionException> {
            client.sendRequest("test.method")
        }
    }

    @Test
    fun `sendRequest uses per-request read timeout`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBody("""{"jsonrpc":"2.0","result":"ok","id":1}""")
        )

        assertFailsWith<SocketTimeoutException> {
            client.sendRequest("test.method", readTimeoutMs = 50)
        }
    }

    @Test
    fun `sendRequest preserves three argument JVM overload`() {
        val overload = AutomationClient::class.java.methods.singleOrNull { method ->
            method.name == "sendRequest" &&
                method.parameterTypes.toList() == listOf(
                String::class.java,
                Map::class.java,
                Int::class.javaPrimitiveType,
                kotlin.coroutines.Continuation::class.java,
            )
        }

        assertTrue(overload != null)
    }

    @Test
    fun `tapOnElement serializes selectors timeout and method`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.tapOnElement(
            AndroidElementSelectors(
                text = "Exact",
                textContains = "Partial",
                resourceId = "com.example:id/login",
                className = "android.widget.Button",
                contentDescription = "Log in",
            ),
            timeoutMs = 5_000,
        )

        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.tapOnElement", body["method"].asString)
        val params = body.getAsJsonObject("params")
        assertEquals("Exact", params["text"].asString)
        assertEquals("Partial", params["textContains"].asString)
        assertEquals("com.example:id/login", params["resourceId"].asString)
        assertEquals("android.widget.Button", params["className"].asString)
        assertEquals("Log in", params["contentDescription"].asString)
        assertEquals(5_000, params["timeoutMs"].asInt)
    }

    @Test
    fun `tapOnElement omits null selectors`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.tapOnElement(AndroidElementSelectors(resourceId = "com.example:id/login"), timeoutMs = 1_000)

        val params = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.getAsJsonObject("params")
        assertEquals(setOf("resourceId", "timeoutMs"), params.keySet())
    }

    @Test
    fun `tapOnElement rejects timeout outside platform range before sending request`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        assertFailsWith<IllegalArgumentException> {
            client.tapOnElement(AndroidElementSelectors(text = "Login"), timeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            client.tapOnElement(
                AndroidElementSelectors(text = "Login"),
                timeoutMs = AutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS.toInt() + 1,
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tapOnElement read timeout includes transport grace`() {
        assertEquals(
            40_000,
            elementTapReadTimeoutMs(
                timeoutMs = AutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS.toInt(),
                maxTimeoutMs = AutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS,
                graceMs = AutomationConfig.ELEMENT_TAP_TRANSPORT_GRACE_MS,
            ),
        )
    }

    @Test
    fun `pressKey serializes numeric and named keys`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.pressKey(66)
        client.pressKey("enter")

        val numeric = requestBody()
        assertEquals("ui.pressKey", numeric["method"].asString)
        assertEquals(mapOf("keyCode" to "66"), numeric["params"].asJsonObject.stringValues())
        val named = requestBody()
        assertEquals("ui.pressKey", named["method"].asString)
        assertEquals(mapOf("action" to "enter"), named["params"].asJsonObject.stringValues())
    }

    @Test
    fun `clearText sends empty params`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.clearText()

        val body = requestBody()
        assertEquals("ui.clearText", body["method"].asString)
        assertTrue(body["params"].asJsonObject.entrySet().isEmpty())
    }

    @Test
    fun `longPress serializes coordinate and selector targets`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.longPress(10, 20)
        client.longPress(AndroidElementSelectors(resourceId = "menu"), 1_500)

        val coordinate = requestBody()
        assertEquals("ui.longPress", coordinate["method"].asString)
        assertEquals(
            mapOf("x" to "10", "y" to "20"),
            coordinate["params"].asJsonObject.stringValues(),
        )
        val selector = requestBody()
        assertEquals("ui.longPress", selector["method"].asString)
        assertEquals(
            mapOf("resourceId" to "menu", "timeoutMs" to "1500"),
            selector["params"].asJsonObject.stringValues(),
        )
    }

    @Test
    fun `doubleTap serializes coordinate and selector targets`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.doubleTap(30, 40)
        client.doubleTap(AndroidElementSelectors(text = "Photo"), 2_000)

        val coordinate = requestBody()
        assertEquals("ui.doubleTap", coordinate["method"].asString)
        assertEquals(mapOf("x" to "30", "y" to "40"), coordinate["params"].asJsonObject.stringValues())
        val selector = requestBody()
        assertEquals("ui.doubleTap", selector["method"].asString)
        assertEquals(
            mapOf("text" to "Photo", "timeoutMs" to "2000"),
            selector["params"].asJsonObject.stringValues(),
        )
    }

    @Test
    fun `targeted input serializes prefixed selectors and timeout`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.inputText(
            text = "Ada",
            selectors = AndroidElementSelectors(
                text = "Name",
                textContains = "Nam",
                resourceId = "name",
                className = "android.widget.EditText",
                contentDescription = "Full name",
            ),
            timeoutMs = 1_500,
        )

        val body = requestBody()
        assertEquals("ui.inputText", body["method"].asString)
        assertEquals(
            mapOf(
                "text" to "Ada",
                "targetText" to "Name",
                "targetTextContains" to "Nam",
                "targetResourceId" to "name",
                "targetClassName" to "android.widget.EditText",
                "targetContentDescription" to "Full name",
                "timeoutMs" to "1500",
            ),
            body["params"].asJsonObject.stringValues(),
        )
    }

    @Test
    fun `focused input preserves the original request shape`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.inputText("Ada")

        assertEquals(mapOf("text" to "Ada"), requestBody()["params"].asJsonObject.stringValues())
    }

    @Test
    fun `legacy focused input descriptor remains available`() {
        assertTrue(
            AutomationClient::class.java.declaredMethods.any {
                it.name == "inputText" && it.parameterTypes.contentEquals(
                    arrayOf(String::class.java, Continuation::class.java),
                )
            },
        )
    }

    private fun requestBody() = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

    private fun com.google.gson.JsonObject.stringValues() =
        entrySet().associate { it.key to it.value.asString }

    // --- isServerRunning ---

    @Test
    fun `isServerRunning returns true on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        assertTrue(client.isServerRunning())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
    }

    @Test
    fun `isServerRunning returns false on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Error"))

        assertFalse(client.isServerRunning())
    }

    @Test
    fun `isServerRunning returns false when connection refused`() = runBlocking {
        // Point to a port with no server
        val deadClient = AutomationClient(host = "localhost", port = 1)

        assertFalse(deadClient.isServerRunning())
    }
}
