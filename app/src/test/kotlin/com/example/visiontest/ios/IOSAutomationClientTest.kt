package com.example.visiontest.ios

import com.example.visiontest.CommandExecutionException
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

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
}
