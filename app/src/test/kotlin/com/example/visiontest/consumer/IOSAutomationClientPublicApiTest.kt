package com.example.visiontest.consumer

import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSElementSelectors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

class IOSAutomationClientPublicApiTest {
    @Test
    fun `tapOnElement is available as a client member`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        server.start()

        try {
            val client = IOSAutomationClient(server.hostName, server.port)

            client.tapOnElement(IOSElementSelectors(identifier = "list"), timeoutMs = 1_000)

            val body = server.takeRequest().body.readUtf8()
            assertEquals(true, body.contains("\"method\":\"ui.tapOnElement\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `swipeOnElement is available as a client member`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        server.start()

        try {
            val client = IOSAutomationClient(server.hostName, server.port)

            client.swipeOnElement("up", IOSElementSelectors(identifier = "list"))

            val body = server.takeRequest().body.readUtf8()
            assertEquals(true, body.contains("\"method\":\"ui.swipeOnElement\""))
        } finally {
            server.shutdown()
        }
    }
}
