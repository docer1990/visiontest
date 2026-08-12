package com.example.visiontest.tools

import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.android.AutomationClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [AndroidWaitToolRegistrar]'s parameter validation and delegation.
 * The polling loop itself is covered in
 * [com.example.visiontest.android.AutomationClientWaitTest].
 */
class AndroidWaitToolRegistrarTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var registrar: AndroidWaitToolRegistrar

    @BeforeTest
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val client = AutomationClient(host = mockServer.hostName, port = mockServer.port)
        registrar = AndroidWaitToolRegistrar(client)
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `waitForElement returns element response when found`() = runBlocking {
        val foundResponse = """{"jsonrpc":"2.0","result":{"found":true,"text":"Login"},"id":1}"""
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        mockServer.enqueue(MockResponse().setBody(foundResponse))

        val result = registrar.waitForElement(AndroidElementSelectors(text = "Login"), timeoutMs = 5000)

        assertTrue(result.contains("\"found\":true"))
    }

    @Test
    fun `waitForElement requires at least one selector`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitForElement(AndroidElementSelectors())
        }
        assertTrue(ex.message!!.contains("At least one selector required"))
    }

    @Test
    fun `waitForElement rejects timeout above the cap`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitForElement(AndroidElementSelectors(text = "Login"), timeoutMs = 31_000)
        }
        assertTrue(ex.message!!.contains("30000"))
    }

    @Test
    fun `waitUntilGone rejects non-positive timeout`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitUntilGone(AndroidElementSelectors(text = "Login"), timeoutMs = 0)
        }
        assertTrue(ex.message!!.contains("timeoutMs"))
    }

    @Test
    fun `waitForElement throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> {
            registrar.waitForElement(AndroidElementSelectors(text = "Login"))
        }
        assertTrue(ex.message!!.contains("not running"))
    }
}
