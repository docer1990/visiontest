package dev.visiontest.ios

import dev.visiontest.performance.OperationOutcome
import dev.visiontest.performance.TraceEvent
import dev.visiontest.performance.TraceMetric
import dev.visiontest.performance.TraceRecorder
import dev.visiontest.performance.TraceStage

import dev.visiontest.CommandExecutionException
import dev.visiontest.performance.NativeTimingEvent
import dev.visiontest.performance.NativeTimingStatus
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
import dev.visiontest.performance.TraceRuntime
import java.nio.file.Files
import dev.visiontest.common.elementTapReadTimeoutMs
import dev.visiontest.config.IOSAutomationConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IOSAutomationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: IOSAutomationClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = IOSAutomationClient(
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
        val traced = IOSAutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody("original"))
        assertEquals("original", traced.sendRequest("device.info"))
        assertEquals(null, server.takeRequest().getHeader("X-VisionTest-Trace"))
        server.enqueue(MockResponse().setBody("original"))
        assertEquals("original", client.sendRequest("device.info"))
        assertEquals(null, server.takeRequest().getHeader("X-VisionTest-Trace"))
        server.enqueue(MockResponse().setBody("ok"))
        assertTrue(trace.invocation("get_device_info", "ios") { traced.isServerRunning() })
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
        val traced = IOSAutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setResponseCode(503).setHeader("X-VisionTest-Timing", "!").setBody("original"))
        val error = assertFailsWith<CommandExecutionException> {
            trace.invocation("get_device_info", "ios") { traced.sendRequest("device.info") }
        }
        assertEquals("HTTP error: 503 - original", error.message)
        assertEquals(NativeTimingStatus.INVALID, native.single().timing.status)
        server.enqueue(MockResponse().setChunkedBody("x".repeat(1024), 64)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertFailsWith<IOException> {
            trace.invocation("get_device_info", "ios") { traced.sendRequest("device.info") }
        }
        assertEquals(NativeTimingStatus.MISSING, native.last().timing.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `concurrent duplicate RPC IDs retain separate native request correlation`() = runBlocking {
        val native = Collections.synchronizedList(mutableListOf<NativeTimingEvent>())
        val trace = TraceRecorder(emit = {}, emitNative = { native.add(it); Unit })
        val traced = IOSAutomationClient(server.hostName, server.port, trace)
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
            async { trace.invocation("get_device_info", "ios") { traced.sendRequest("device.info", id = 7) } }
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
        val traced = IOSAutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody("original"))
        assertEquals("original", trace.invocation("get_device_info", "ios") { traced.sendRequest("device.info") })
        server.enqueue(MockResponse().setResponseCode(503).setBody("original"))
        val error = assertFailsWith<CommandExecutionException> {
            trace.invocation("get_device_info", "ios") { traced.sendRequest("device.info") }
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
            val traced = IOSAutomationClient(server.hostName, server.port, runtime.recorder)
            val bodies = listOf("""{"jsonrpc":"2.0","result":{"success":true},"id":7}""",
                """{"jsonrpc":"2.0","error":{"code":-1,"message":"original"},"id":7}""")
            for (body in bodies) {
                for (header in listOf(null, "!")) {
                    val response = MockResponse().setBody(body)
                    header?.let { response.setHeader("X-VisionTest-Timing", it) }
                    server.enqueue(response)
                    assertEquals(body, runtime.recorder.invocation("get_device_info", "ios") {
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

    @Test
    fun `swipeOnElement serializes all selectors with Android wire names`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        client.swipeOnElement(
            "left",
            IOSElementSelectors("Exact", "Partial", "carousel", "ScrollView", "Photos", "com.example"),
            "fast"
        )
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.swipeOnElement", body["method"].asString)
        val params = body.getAsJsonObject("params")
        val expected = mapOf("direction" to "left", "text" to "Exact", "textContains" to "Partial",
            "resourceId" to "carousel", "className" to "ScrollView", "contentDescription" to "Photos",
            "bundleId" to "com.example", "speed" to "fast")
        assertEquals(expected, params.entrySet().associate { it.key to it.value.asString })
    }

    @Test
    fun `swipeOnElement defaults speed and omits absent selectors`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        client.swipeOnElement("up", IOSElementSelectors(identifier = "list"))
        val params = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.getAsJsonObject("params")
        assertEquals(mapOf("direction" to "up", "resourceId" to "list", "speed" to "normal"),
            params.entrySet().associate { it.key to it.value.asString })
    }

    @Test
    fun `tapOnElement serializes selectors app scope timeout and method`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.tapOnElement(
            IOSElementSelectors(
                text = "Exact",
                textContains = "Partial",
                identifier = "login",
                elementType = "Button",
                label = "Log in",
                bundleId = "com.example.app",
            ),
            timeoutMs = 5_000,
        )

        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.tapOnElement", body["method"].asString)
        val params = body.getAsJsonObject("params")
        assertEquals("Exact", params["text"].asString)
        assertEquals("Partial", params["textContains"].asString)
        assertEquals("login", params["resourceId"].asString)
        assertEquals("Button", params["className"].asString)
        assertEquals("Log in", params["contentDescription"].asString)
        assertEquals("com.example.app", params["bundleId"].asString)
        assertEquals(5_000, params["timeoutMs"].asInt)
    }

    @Test
    fun `tapOnElement omits null selectors and bundleId`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        client.tapOnElement(IOSElementSelectors(identifier = "login"), timeoutMs = 1_000)

        val params = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.getAsJsonObject("params")
        assertEquals(setOf("resourceId", "timeoutMs"), params.keySet())
    }

    @Test
    fun `tapOnElement rejects timeout outside platform range before sending request`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))

        assertFailsWith<IllegalArgumentException> {
            client.tapOnElement(IOSElementSelectors(text = "Login"), timeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            client.tapOnElement(
                IOSElementSelectors(text = "Login"),
                timeoutMs = IOSAutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS.toInt() + 1,
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tapOnElement read timeout includes transport grace`() {
        assertEquals(
            40_000,
            elementTapReadTimeoutMs(
                timeoutMs = IOSAutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS.toInt(),
                maxTimeoutMs = IOSAutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS,
                graceMs = IOSAutomationConfig.ELEMENT_TAP_TRANSPORT_GRACE_MS,
            ),
        )
    }

    @Test
    fun `longPress coordinate and selector overloads serialize exact requests`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"result":{"success":true}}""")) }
        client.longPress(10, 20)
        client.longPress(
            IOSElementSelectors("Exact", "Partial", "menu", "Button", "Open menu", "app.id"),
            1_500,
        )

        val longPress = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.longPress", longPress["method"].asString)
        assertEquals(mapOf("x" to "10", "y" to "20"), longPress.stringParams())
        val selectorLongPress = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.longPress", selectorLongPress["method"].asString)
        assertEquals(allSelectorParams(1_500), selectorLongPress.stringParams())
    }

    @Test
    fun `doubleTap coordinate and selector overloads serialize exact requests`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"result":{"success":true}}""")) }
        client.doubleTap(30, 40)
        client.doubleTap(
            IOSElementSelectors("Exact", "Partial", "menu", "Button", "Open menu", "app.id"),
            2_500,
        )

        val coordinateDoubleTap = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.doubleTap", coordinateDoubleTap["method"].asString)
        assertEquals(mapOf("x" to "30", "y" to "40"), coordinateDoubleTap.stringParams())
        val selectorDoubleTap = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.doubleTap", selectorDoubleTap["method"].asString)
        assertEquals(allSelectorParams(2_500), selectorDoubleTap.stringParams())
    }

    @Test
    fun `dismissKeyboard and handleAlert omit absent optional parameters`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"result":{"success":true}}""")) }
        client.dismissKeyboard()
        client.handleAlert("dismiss")

        val dismiss = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.dismissKeyboard", dismiss["method"].asString)
        assertEquals(emptyMap(), dismiss.stringParams())
        val alert = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.handleAlert", alert["method"].asString)
        assertEquals(mapOf("action" to "dismiss"), alert.stringParams())
    }

    @Test
    fun `dismissKeyboard serializes exact app scope`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        client.dismissKeyboard("app.id")

        val dismiss = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.dismissKeyboard", dismiss["method"].asString)
        assertEquals(mapOf("bundleId" to "app.id"), dismiss.stringParams())
    }

    @Test
    fun `handleAlert serializes exact action label and app scope`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        client.handleAlert("accept", "Allow", "app.id")

        val alert = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.handleAlert", alert["method"].asString)
        assertEquals(
            mapOf("action" to "accept", "buttonLabel" to "Allow", "bundleId" to "app.id"),
            alert.stringParams(),
        )
    }

    @Test
    fun `targeted input maps all selectors app scope and timeout`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        client.inputText(
            "Ada",
            "app.id",
            IOSElementSelectors("Exact", "Partial", "name", "TextField", "Name"),
            2_000,
        )

        val request = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.inputText", request["method"].asString)
        assertEquals(
            mapOf(
                "text" to "Ada",
                "bundleId" to "app.id",
                "targetText" to "Exact",
                "targetTextContains" to "Partial",
                "targetResourceId" to "name",
                "targetClassName" to "TextField",
                "targetContentDescription" to "Name",
                "timeoutMs" to "2000",
            ),
            request.stringParams(),
        )
    }

    @Test
    fun `focused input request stays unchanged`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"result":{"success":true}}""")) }
        client.inputText("focused", "app.id")
        client.inputText("springboard")

        val scoped = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.inputText", scoped["method"].asString)
        assertEquals(mapOf("text" to "focused", "bundleId" to "app.id"), scoped.stringParams())
        val unscoped = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.inputText", unscoped["method"].asString)
        assertEquals(mapOf("text" to "springboard"), unscoped.stringParams())
    }

    private fun com.google.gson.JsonObject.stringParams(): Map<String, String> =
        getAsJsonObject("params").entrySet().associate { it.key to it.value.asString }

    private fun allSelectorParams(timeoutMs: Int) = mapOf(
        "timeoutMs" to timeoutMs.toString(),
        "text" to "Exact",
        "textContains" to "Partial",
        "resourceId" to "menu",
        "className" to "Button",
        "contentDescription" to "Open menu",
        "bundleId" to "app.id",
    )

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
    fun `sendRequest uses Gson serialization for request body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("test.method", mapOf("key" to "value"))

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("2.0", body.get("jsonrpc").asString)
        assertEquals("test.method", body.get("method").asString)
        assertEquals(1, body.get("id").asInt)
        val params = body.getAsJsonObject("params")
        assertEquals("value", params.get("key").asString)
    }

    @Test
    fun `sendRequest sends empty params map when null`() = runBlocking {
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
    fun `sendRequest serializes int params via Gson`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("ui.tapByCoordinates", mapOf("x" to 100, "y" to 200))

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertEquals(100, params.get("x").asInt)
        assertEquals(200, params.get("y").asInt)
    }

    @Test
    fun `sendRequest serializes boolean params via Gson`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest("ui.getInteractiveElements", mapOf("includeDisabled" to true))

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertTrue(params.get("includeDisabled").asBoolean)
    }

    @Test
    fun `sendRequest includes bundleId in params when provided`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":1}"""))

        client.sendRequest(
            "ui.dumpHierarchy",
            mapOf("bundleId" to "com.example.app")
        )

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertEquals("com.example.app", params.get("bundleId").asString)
    }

    @Test
    fun `sendRequest uses custom id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"ok","id":7}"""))

        client.sendRequest("test.method", id = 7)

        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(7, body.get("id").asInt)
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
        val deadClient = IOSAutomationClient(host = "localhost", port = 1)

        assertFalse(deadClient.isServerRunning())
    }
    @Test
    fun `traced iOS gone wait records successful polling without exposing selectors`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"found":false}}"""))
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val tracedClient = IOSAutomationClient(server.hostName, server.port, trace)
        val result = trace.invocation("ios_wait_until_gone", "ios") {
            tracedClient.pollForElement(true, 5000, 100, "private-selector") {
                tracedClient.findElement(text = "private-selector")
            }
        }
        assertEquals("Element is no longer present (private-selector).", result)
        val poll = events.single { it.stage == TraceStage.POLL }
        assertEquals(OperationOutcome.SUCCESS, poll.operationOutcome)
        assertEquals(1L, poll.metrics[TraceMetric.POLL_COUNT])
        assertEquals(1, server.requestCount)
        assertFalse(events.joinToString { it.toJson().toString() }.contains("private-selector"))
    }

}
