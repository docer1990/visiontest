package dev.visiontest.tools

import dev.visiontest.ServerNotRunningException
import dev.visiontest.android.AndroidElementSelectors
import dev.visiontest.android.AutomationClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AndroidWaitToolRegistrar]'s parameter validation and delegation.
 * The polling loop itself is covered in
 * [dev.visiontest.android.AutomationClientWaitTest].
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
    fun `structured disappearance returns matched response`() = runBlocking {
        val response = """{"jsonrpc":"2.0","result":{"found":false},"id":9}"""
        mockServer.enqueue(MockResponse().setBody("OK"))
        mockServer.enqueue(MockResponse().setBody("""{"result":{"found":true}}"""))
        mockServer.enqueue(MockResponse().setBody(response))

        assertEquals(response, registrar.waitResult(AndroidElementSelectors(text = "Loading"), expectGone = true))
        assertEquals(3, mockServer.requestCount)
    }

    @Test
    fun `structured appearance returns matched response`() = runBlocking {
        val response = """{"jsonrpc":"2.0","result":{"found":true,"text":"Login"},"id":8}"""
        mockServer.enqueue(MockResponse().setBody("OK"))
        mockServer.enqueue(MockResponse().setBody(response))

        assertEquals(response, registrar.waitResult(AndroidElementSelectors(text = "Login")))
    }

    @Test
    fun `structured disappearance rejects malformed responses`() = runBlocking {
        for (response in listOf("not json", "{}", """{"result":{"found":"false"}}""",
            """{"error":{"code":-32000,"message":"failed"}}""")) {
            mockServer.enqueue(MockResponse().setBody("OK"))
            mockServer.enqueue(MockResponse().setBody(response))
            assertFailsWith<dev.visiontest.CommandExecutionException> {
                registrar.waitResult(AndroidElementSelectors(text = "Loading"), expectGone = true)
            }
        }
    }

    @Test
    fun `structured waits validate before health check`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            registrar.waitResult(AndroidElementSelectors(text = "Loading"), timeoutMs = 0, expectGone = true)
        }
        assertFailsWith<IllegalArgumentException> {
            registrar.waitResult(AndroidElementSelectors(text = " "))
        }
        assertEquals(0, mockServer.requestCount)
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
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitForElement(AndroidElementSelectors())
        }
        assertTrue(ex.message!!.contains("At least one selector required"))
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `waitForElement rejects blank selector before contacting server`() = runBlocking {
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitForElement(AndroidElementSelectors(text = " "))
        }
        assertTrue(ex.message!!.contains("must not be blank"))
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `waitForElement rejects timeout above the cap`() = runBlocking {
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitForElement(AndroidElementSelectors(text = "Login"), timeoutMs = 31_000)
        }
        assertTrue(ex.message!!.contains("30000"))
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `waitUntilGone rejects non-positive timeout`() = runBlocking {
        val ex = assertFailsWith<IllegalArgumentException> {
            registrar.waitUntilGone(AndroidElementSelectors(text = "Login"), timeoutMs = 0)
        }
        assertTrue(ex.message!!.contains("timeoutMs"))
        assertEquals(0, mockServer.requestCount)
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
