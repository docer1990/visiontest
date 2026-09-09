package com.example.visiontest.android

import com.example.visiontest.CommandExecutionException
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.SocketTimeoutException
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
