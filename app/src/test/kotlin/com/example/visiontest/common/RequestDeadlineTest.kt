package com.example.visiontest.common

import com.example.visiontest.android.AutomationClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails

class RequestDeadlineTest {
    @Test
    fun `a lost action response has a bounded wait and possible dispatch`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client = AutomationClient(server.hostName, server.port)
            val budget = RequestDeadline(300)
            val start = System.nanoTime()
            assertFails { withContext(budget) { client.sendRequest("ui.tap", mapOf("x" to 10, "y" to 20), readTimeoutMs = 2000) } }
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
