package com.example.visiontest.android

import com.example.visiontest.performance.OperationOutcome
import com.example.visiontest.performance.TraceEvent
import com.example.visiontest.performance.TraceMetric
import com.example.visiontest.performance.TraceOutcome
import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.performance.TraceStage
import com.example.visiontest.CommandExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the shared element-polling loop (JsonRpcHttpClient.pollForElement),
 * exercised through the Android client against a MockWebServer. A short poll
 * interval keeps the suite fast; production callers use the configured 500ms.
 */
class AutomationClientWaitTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AutomationClient

    private val foundResponse =
        """{"jsonrpc":"2.0","result":{"found":true,"text":"Login","bounds":"[0,0][100,50]"},"id":1}"""
    private val notFoundResponse =
        """{"jsonrpc":"2.0","result":{"found":false},"id":1}"""
    private val errorResponse =
        """{"jsonrpc":"2.0","error":{"code":-32000,"message":"UiAutomator crashed"},"id":1}"""

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AutomationClient(host = server.hostName, port = server.port)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun poll(expectGone: Boolean, timeoutMs: Long, description: String = "text='Login'"): String {
        return client.pollForElement(
            expectGone = expectGone,
            timeoutMs = timeoutMs,
            pollIntervalMs = 100,
            selectorDescription = description,
        ) {
            client.findElement(text = "Login")
        }
    }

    @Test
    fun `traced appearance counts attempts and explicit waits`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        client = AutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody(notFoundResponse))
        server.enqueue(MockResponse().setBody(foundResponse))
        val result = trace.invocation("wait_for_element", "android") {
            poll(expectGone = false, timeoutMs = 5000)
        }
        assertEquals(foundResponse, result)
        assertEquals(2, server.requestCount)
        val polling = events.single { it.stage == TraceStage.POLL }
        assertEquals(2L, polling.metrics[TraceMetric.POLL_COUNT])
        assertTrue(polling.metrics.getValue(TraceMetric.WAIT_NS) >= 0L)
        assertEquals(OperationOutcome.SUCCESS, polling.operationOutcome)
        assertEquals(1, events.count { it.stage == TraceStage.POLL_WAIT })
    }

    @Test
    fun `traced gone wait counts a failed find without treating it as absence`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        client = AutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody(errorResponse))
        trace.invocation("wait_until_gone", "android") {
            assertFailsWith<CommandExecutionException> { poll(expectGone = true, timeoutMs = 5000) }
        }
        assertEquals(1, server.requestCount)
        val polling = events.single { it.stage == TraceStage.POLL }
        assertEquals(1L, polling.metrics[TraceMetric.POLL_COUNT])
        assertEquals(TraceOutcome.THROWN, polling.outcome)
    }

    @Test
    fun `traced malformed poll records processing failure`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        client = AutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody("""{"result":{"found":"false"}}"""))
        trace.invocation("wait_until_gone", "android") {
            assertFailsWith<CommandExecutionException> { poll(expectGone = true, timeoutMs = 5000) }
        }
        assertEquals(1, server.requestCount)
        assertEquals(1, events.count { it.stage == TraceStage.RESPONSE_PROCESS && it.outcome == TraceOutcome.THROWN })
    }

    @Test
    fun `traced exhausted poll budget retains timeout without extra finds`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        client = AutomationClient(server.hostName, server.port, trace)
        server.enqueue(MockResponse().setBody(notFoundResponse))
        trace.invocation("wait_for_element", "android") {
            assertFailsWith<TimeoutException> { poll(expectGone = false, timeoutMs = 0) }
        }
        val polling = events.single { it.stage == TraceStage.POLL }
        assertEquals(TraceOutcome.TIMEOUT, polling.outcome)
        assertEquals(1L, polling.metrics[TraceMetric.POLL_COUNT])
        assertEquals(1, server.requestCount)
        assertEquals(0, events.count { it.stage == TraceStage.POLL_WAIT })
    }

    @Test
    fun `traced interrupted explicit wait accumulates wait time and preserves cancellation`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        client = AutomationClient(server.hostName, server.port, trace)
        val findCalled = CompletableDeferred<Unit>()
        val call = async {
            trace.invocation("wait_for_element", "android") {
                client.pollForElement(false, 5000, 1000, "secret-selector") {
                    findCalled.complete(Unit)
                    notFoundResponse
                }
            }
        }
        findCalled.await()
        call.cancel()
        assertFailsWith<CancellationException> { call.await() }
        val polling = events.single { it.stage == TraceStage.POLL }
        assertEquals(TraceOutcome.CANCELLED, polling.outcome)
        assertEquals(1L, polling.metrics[TraceMetric.POLL_COUNT])
        assertTrue(polling.metrics.getValue(TraceMetric.WAIT_NS) >= 0L)
        assertEquals(TraceOutcome.CANCELLED, events.single { it.stage == TraceStage.POLL_WAIT }.outcome)
    }

    // --- waiting for appearance ---

    @Test
    fun `returns response when element found immediately`() = runBlocking {
        server.enqueue(MockResponse().setBody(foundResponse))

        val result = poll(expectGone = false, timeoutMs = 5000)

        assertEquals(foundResponse, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `returns once element appears after polls`() = runBlocking {
        server.enqueue(MockResponse().setBody(notFoundResponse))
        server.enqueue(MockResponse().setBody(notFoundResponse))
        server.enqueue(MockResponse().setBody(foundResponse))

        val result = poll(expectGone = false, timeoutMs = 5000)

        assertEquals(foundResponse, result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `times out with selectors and elapsed time in message`() = runBlocking {
        server.enqueue(MockResponse().setBody(notFoundResponse))

        val ex = assertFailsWith<TimeoutException> {
            poll(expectGone = false, timeoutMs = 90, description = "text='Login', resourceId='btn_login'")
        }
        assertTrue(ex.message!!.contains("text='Login'"), "message should name the selectors: ${ex.message}")
        assertTrue(ex.message!!.contains("resourceId='btn_login'"))
        assertTrue(ex.message!!.contains("90ms"), "message should include the configured timeout: ${ex.message}")
    }

    // --- waiting for disappearance ---

    @Test
    fun `gone wait succeeds once element disappears`() = runBlocking {
        server.enqueue(MockResponse().setBody(foundResponse))
        server.enqueue(MockResponse().setBody(notFoundResponse))

        val result = poll(expectGone = true, timeoutMs = 5000, description = "text='Loading'")

        assertTrue(result.contains("no longer present"))
        assertTrue(result.contains("text='Loading'"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `gone wait times out while element still present`() = runBlocking {
        server.enqueue(MockResponse().setBody(foundResponse))

        val ex = assertFailsWith<TimeoutException> {
            poll(expectGone = true, timeoutMs = 90)
        }
        assertTrue(ex.message!!.contains("still present"))
    }

    @Test
    fun `gone wait fails on JSON-RPC error instead of reporting gone`() = runBlocking {
        server.enqueue(MockResponse().setBody(errorResponse))

        val ex = assertFailsWith<CommandExecutionException> {
            poll(expectGone = true, timeoutMs = 5000)
        }
        assertTrue(ex.message!!.contains("UiAutomator crashed"))
    }

    @Test
    fun `gone wait fails when server dies mid-wait`() = runBlocking {
        server.enqueue(MockResponse().setBody(foundResponse))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        // Depending on HttpURLConnection's retry behavior the failure surfaces as an
        // IOException or a CommandExecutionException — either way it must be an error,
        // never a "gone" success and never a wait timeout.
        val ex = assertFails {
            poll(expectGone = true, timeoutMs = 5000)
        }
        assertTrue(
            ex is IOException || ex is CommandExecutionException,
            "expected a server failure, got: $ex"
        )
    }

    // --- malformed responses ---

    @Test
    fun `wait fails on response without result object`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"not-an-object","id":1}"""))

        val ex = assertFailsWith<CommandExecutionException> {
            poll(expectGone = false, timeoutMs = 5000)
        }
        assertTrue(ex.message!!.contains("no result object"))
    }

    @Test
    fun `wait fails on malformed JSON response`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json at all"))

        val ex = assertFailsWith<CommandExecutionException> {
            poll(expectGone = false, timeoutMs = 5000)
        }
        assertTrue(ex.message!!.isNotBlank())
    }

    @Test
    fun `gone wait fails when result has no found field instead of reporting gone`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":{"text":"Loading"},"id":1}"""))

        val ex = assertFailsWith<CommandExecutionException> {
            poll(expectGone = true, timeoutMs = 5000)
        }
        assertTrue(ex.message!!.contains("'found'"))
    }

    @Test
    fun `gone wait fails when found is not a boolean`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":{"found":"yes"},"id":1}"""))

        val ex = assertFailsWith<CommandExecutionException> {
            poll(expectGone = true, timeoutMs = 5000)
        }
        assertTrue(ex.message!!.contains("'found'"))
    }
}
