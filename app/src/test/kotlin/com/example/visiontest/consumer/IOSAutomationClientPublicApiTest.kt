package com.example.visiontest.consumer

import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSElementSelectors
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IOSAutomationClientPublicApiTest {
    @Test
    fun `inputText retains its previous JVM ABI`() {
        IOSAutomationClient::class.java.getDeclaredMethod(
            "inputText",
            String::class.java,
            String::class.java,
            Continuation::class.java,
        )

        val defaultBridge = IOSAutomationClient::class.java.getDeclaredMethod(
            "inputText\$default",
            IOSAutomationClient::class.java,
            String::class.java,
            String::class.java,
            Continuation::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        assertTrue(Modifier.isStatic(defaultBridge.modifiers))
    }

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

    @Test
    fun `missing interaction members and overloads compile for consumers`() = runBlocking {
        val server = MockWebServer()
        repeat(8) { server.enqueue(MockResponse().setBody("""{"result":{"success":true}}""")) }
        server.start()
        try {
            val client = IOSAutomationClient(server.hostName, server.port)
            client.longPress(1, 2)
            client.longPress(IOSElementSelectors(identifier = "menu", bundleId = "app.id"), 1_000)
            client.doubleTap(3, 4)
            client.doubleTap(IOSElementSelectors(label = "Item", bundleId = "app.id"), 2_000)
            client.dismissKeyboard()
            client.handleAlert("dismiss")
            client.inputText("focused", "app.id")
            client.inputText("Ada", "app.id", IOSElementSelectors(identifier = "name"), 3_000)

            val requests = List(8) {
                JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            }
            assertEquals(
                listOf(
                    "ui.longPress",
                    "ui.longPress",
                    "ui.doubleTap",
                    "ui.doubleTap",
                    "ui.dismissKeyboard",
                    "ui.handleAlert",
                    "ui.inputText",
                    "ui.inputText",
                ),
                requests.map { it["method"].asString },
            )
            assertEquals(setOf("x", "y"), requests[0]["params"].asJsonObject.keySet())
            assertEquals("menu", requests[1]["params"].asJsonObject["resourceId"].asString)
            assertEquals(setOf("x", "y"), requests[2]["params"].asJsonObject.keySet())
            assertEquals("Item", requests[3]["params"].asJsonObject["contentDescription"].asString)
            assertTrue(requests[4]["params"].asJsonObject.isEmpty)
            assertEquals(setOf("action"), requests[5]["params"].asJsonObject.keySet())
            assertEquals(setOf("text", "bundleId"), requests[6]["params"].asJsonObject.keySet())
            assertEquals(
                setOf("text", "bundleId", "targetResourceId", "timeoutMs"),
                requests[7]["params"].asJsonObject.keySet(),
            )
        } finally {
            server.shutdown()
        }
    }
}
