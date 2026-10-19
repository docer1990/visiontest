package com.example.visiontest.common

import com.example.visiontest.android.AutomationClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.util.concurrent.TimeoutException
import kotlin.test.assertFails

class RequestDeadlineTest {
    @Test
    fun `deadline bounds delayed response body after headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("response").setBodyDelay(2, TimeUnit.SECONDS))
            server.start()
            val budget = RequestDeadline(250)
            val start = System.nanoTime()

            assertFailsWith<TimeoutException> {
                budget.exchange(server.url("/jsonrpc").toString(), "{}".toByteArray(), "ui.tapByCoordinates", 5000)
            }

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
            assertTrue(budget.actionDispatched)
        }
    }

    @Test
    fun `method timeout bounds continuously arriving response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("response-body").throttleBody(1, 200, TimeUnit.MILLISECONDS))
            server.start()
            val budget = RequestDeadline(5000)
            val start = System.nanoTime()

            assertFailsWith<TimeoutException> {
                budget.exchange(server.url("/jsonrpc").toString(), "{}".toByteArray(), "ui.tapByCoordinates", 250)
            }

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
        }
    }

    @Test
    fun `parent timeout remains coroutine cancellation`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("response").setBodyDelay(2, TimeUnit.SECONDS))
            server.start()
            val budget = RequestDeadline(5000)

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) {
                    budget.exchange(server.url("/jsonrpc").toString(), "{}".toByteArray(), "ui.tapByCoordinates", 5000)
                }
            }
            Unit
        }
    }

    @Test
    fun `read operations never indicate action dispatch`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (method in listOf("ui.dumpHierarchy", "device.getInfo", "ui.findElement",
                "ui.getInteractiveElements", "ui.screenshot")) {
                server.enqueue(MockResponse().setBody("{}"))
                val budget = RequestDeadline(5000)

                budget.exchange(server.url("/jsonrpc").toString(), "{}".toByteArray(), method, 5000)

                assertFalse(budget.actionDispatched, method)
            }
        }
    }

    @Test
    fun `a lost action response has a bounded wait and possible dispatch`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client = AutomationClient(server.hostName, server.port)
            val budget = RequestDeadline(300)
            val start = System.nanoTime()
            assertFails {
                withContext(budget) {
                    client.sendRequest("ui.tapByCoordinates", mapOf("x" to 10, "y" to 20), readTimeoutMs = 2000)
                }
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
            assertTrue(budget.actionDispatched)
            assertTrue(server.requestCount <= 1)
        }
    }

    @Test
    fun `health timeout does not indicate action dispatch`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("OK").setHeadersDelay(2, TimeUnit.SECONDS))
            server.start()
            val client = AutomationClient(server.hostName, server.port)
            val budget = RequestDeadline(200)
            val start = System.nanoTime()
            assertFalse(withContext(budget) { client.isServerRunning() })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
            assertFalse(budget.actionDispatched)
        }
    }
}
