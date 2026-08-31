package com.example.visiontest.tools

import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSElementSelectors
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [IOSWaitToolRegistrar]'s parameter validation and iOS selector mapping.
 * The polling loop itself is covered in
 * [com.example.visiontest.android.AutomationClientWaitTest].
 */
class IOSWaitToolRegistrarTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var registrar: IOSWaitToolRegistrar

    @BeforeTest
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        registrar = IOSWaitToolRegistrar(IOSAutomationClient(host = mockServer.hostName, port = mockServer.port))
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `waitForElement maps selectors to findElement params`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        mockServer.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","result":{"found":true,"label":"Settings"},"id":1}""")
        )

        val selectors = IOSElementSelectors(
            identifier = "settingsButton",
            elementType = "Button",
            bundleId = "com.apple.Preferences",
        )
        registrar.waitForElement(selectors, timeoutMs = 5000)

        mockServer.takeRequest() // health check
        val body = JsonParser.parseString(mockServer.takeRequest().body.readUtf8()).asJsonObject
        val params = body.getAsJsonObject("params")
        assertEquals("ui.findElement", body.get("method").asString)
        assertEquals("settingsButton", params.get("resourceId").asString)
        assertEquals("Button", params.get("className").asString)
        assertEquals("com.apple.Preferences", params.get("bundleId").asString)
    }

    @Test
    fun `waitForElement requires a selector beyond bundleId`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitForElement(IOSElementSelectors(bundleId = "com.example.app"))
        }
        assertTrue(ex.message!!.contains("At least one selector required"))
    }

    @Test
    fun `waitUntilGone rejects timeout above the cap`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitUntilGone(IOSElementSelectors(text = "Loading"), timeoutMs = 31_000)
        }
        assertTrue(ex.message!!.contains("30000"))
    }

    @Test
    fun `waitForElement throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> {
            registrar.waitForElement(IOSElementSelectors(text = "Login"))
        }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `timeout message uses tool-facing selector names`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        mockServer.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":{"found":false},"id":1}"""))

        val selectors = IOSElementSelectors(identifier = "loginBtn", bundleId = "com.example.app")
        val ex = assertFailsWith<TimeoutException> {
            registrar.waitForElement(selectors, timeoutMs = 400)
        }
        // Callers pass resourceId/className/contentDescription, so the message must not
        // echo back the internal identifier/elementType/label names.
        assertTrue(ex.message!!.contains("resourceId='loginBtn'"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("bundleId='com.example.app'"))
        assertFalse(ex.message!!.contains("identifier='"))
    }

    @Test
    fun `hasAnySelector is false when only bundleId is set`() {
        assertFalse(IOSElementSelectors(bundleId = "com.example.app").hasAnySelector())
        assertTrue(IOSElementSelectors(text = "x").hasAnySelector())
    }
}
