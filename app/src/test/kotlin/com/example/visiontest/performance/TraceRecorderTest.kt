package com.example.visiontest.performance

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.NoSimulatorAvailableException
import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.cli.CliExit
import com.example.visiontest.cli.ExitCode
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraceRecorderTest {
    @Test
    fun `nested spans share invocation and retain monotonic durations`() = runTest {
        var now = 10L
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { now }, emit = { events.add(it); Unit })

        trace.invocation("get_device_info", "android") {
            trace.span(TraceStage.HTTP_EXCHANGE) { now = 25L }
        }

        val child = events.single { it.stage == TraceStage.HTTP_EXCHANGE }
        val parent = events.single { it.stage == TraceStage.INVOCATION }
        assertEquals(15L, child.durationNs)
        assertEquals(parent.spanId, child.parentSpanId)
        assertEquals(parent.invocationId, child.invocationId)
        assertEquals(TraceOutcome.RETURNED, parent.outcome)
        assertEquals(0L, parent.startOffsetNs)
        assertEquals(15L, parent.durationNs)
        assertNull(parent.parentSpanId)
        assertEquals(parent.sessionId, child.sessionId)
        listOf(parent.sessionId, parent.invocationId, parent.spanId, child.spanId).forEach {
            assertTrue(it.matches(Regex("[0-9a-f]{32}")))
        }
    }

    @Test
    fun `overlapping invocations keep distinct identities after suspension`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val now = AtomicLong(100L)
        val trace = TraceRecorder(nowNs = now::get, emit = { events.add(it); Unit })
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            trace.invocation("find_element", "android") {
                trace.span(TraceStage.OPERATION) {
                    firstEntered.complete(Unit)
                    secondEntered.await()
                    now.set(120L)
                    trace.span(TraceStage.POLL) { delay(1) }
                }
                trace.span(TraceStage.HEALTH) { Unit }
            }
        }
        val second = async {
            firstEntered.await()
            trace.invocation("ios_find_element", "ios") {
                secondEntered.complete(Unit)
                trace.span(TraceStage.POLL) { delay(2) }
            }
        }
        awaitAll(first, second)

        val invocations = events.filter { it.stage == TraceStage.INVOCATION }
        assertEquals(2, invocations.map { it.invocationId }.distinct().size)
        assertEquals(listOf(1L, 2L), invocations.map { it.invocationSequence }.sorted())
        invocations.forEach { invocation ->
            val children = events.filter { it.invocationId == invocation.invocationId }
            children.forEach {
                assertEquals(invocation.operation, it.operation)
                assertEquals(invocation.platform, it.platform)
                assertEquals(invocation.invocationSequence, it.invocationSequence)
            }
            val poll = children.single { it.stage == TraceStage.POLL }
            val operation = children.singleOrNull { it.stage == TraceStage.OPERATION }
            assertEquals(operation?.spanId ?: invocation.spanId, poll.parentSpanId)
        }
        val health = events.single { it.stage == TraceStage.HEALTH }
        assertEquals(invocations.single { it.operation == "find_element" }.spanId, health.parentSpanId)
    }

    @Test
    fun `dispatcher changes preserve context and concurrent metrics`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })

        trace.invocation("screenshot", "ios") {
            trace.span(TraceStage.SCREENSHOT_DECODE) {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        List(100) { async { trace.metric(TraceMetric.DECODED_BYTES, 2L) } }.awaitAll()
                    }
                    trace.operationOutcome(OperationOutcome.SUCCESS)
                    trace.span(TraceStage.SCREENSHOT_WRITE) { Unit }
                }
            }
        }

        val decode = events.single { it.stage == TraceStage.SCREENSHOT_DECODE }
        val write = events.single { it.stage == TraceStage.SCREENSHOT_WRITE }
        val root = events.single { it.stage == TraceStage.INVOCATION }
        assertEquals(200L, decode.metrics[TraceMetric.DECODED_BYTES])
        assertEquals(OperationOutcome.SUCCESS, decode.operationOutcome)
        assertEquals(OperationOutcome.UNKNOWN, root.operationOutcome)
        assertEquals(root.spanId, decode.parentSpanId)
        assertEquals(decode.spanId, write.parentSpanId)
        assertEquals(root.invocationId, write.invocationId)
        assertTrue(root.metrics.isEmpty())
    }

    @Test
    fun `span completion waits for inherited structured children`() = runTest {
        var now = 0L
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { now }, emit = { events.add(it); Unit })
        trace.invocation("wait_for_element", "android") {
            CoroutineScope(currentCoroutineContext()).launch {
                delay(1)
                now = 20L
                trace.metric(TraceMetric.POLL_COUNT, 1L)
                trace.span(TraceStage.POLL) { Unit }
            }
        }
        val root = events.single { it.stage == TraceStage.INVOCATION }
        assertEquals(20L, root.durationNs)
        assertEquals(1L, root.metrics[TraceMetric.POLL_COUNT])
        assertEquals(TraceStage.INVOCATION, events.last().stage)
    }

    @Test
    fun `inherited child failure preserves exact original throwable`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        val original = IOException("private child failure")

        val caught = assertFailsWith<IOException> {
            trace.invocation("get_device_info", "android") {
                CoroutineScope(currentCoroutineContext()).launch {
                    delay(1)
                    throw original
                }
            }
        }

        assertSame(original, caught)
        assertEquals(TraceOutcome.THROWN, events.single().outcome)
        assertEquals(TraceErrorCategory.IO, events.single().errorCategory)
    }

    @Test
    fun `inherited child failure on IO dispatcher preserves exact original throwable`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        val original = IOException("private child failure")
        val mayFail = CompletableDeferred<Unit>()

        val caught = assertFailsWith<IOException> {
            trace.invocation("get_device_info", "android") {
                CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                    mayFail.await()
                    throw original
                }
                mayFail.complete(Unit)
            }
        }

        assertSame(original, caught)
        assertEquals(TraceOutcome.THROWN, events.single().outcome)
    }

    @Test
    fun `child failure remains authoritative when it cancels the suspended block`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        val original = IOException("private child failure")

        val caught = assertFailsWith<IOException> {
            trace.invocation("get_device_info", "android") {
                CoroutineScope(currentCoroutineContext()).launch {
                    delay(1)
                    throw original
                }
                awaitCancellation()
            }
        }

        assertSame(original, caught)
        assertEquals(TraceOutcome.THROWN, events.single().outcome)
    }

    @Test
    fun `scope cancellation preserves the original completion cause`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        val original = CancellationException("private scope cancellation")

        val caught = assertFailsWith<CancellationException> {
            trace.invocation("get_device_info", "android") {
                checkNotNull(currentCoroutineContext()[Job]).cancel(original)
                awaitCancellation()
            }
        }

        assertSame(original, caught)
        assertEquals(TraceOutcome.CANCELLED, events.single().outcome)
    }

    @Test
    fun `nested failures retain exact throwable and restored parent`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        val original = IllegalArgumentException("sensitive input")

        trace.invocation("get_device_info", "android") {
            val caught = assertFailsWith<IllegalArgumentException> {
                trace.span(TraceStage.REQUEST_PREPARE) { throw original }
            }
            assertSame(original, caught)
            trace.span(TraceStage.HEALTH) { Unit }
        }

        assertEquals(TraceOutcome.THROWN, events.first().outcome)
        assertEquals(TraceErrorCategory.VALIDATION, events.first().errorCategory)
        assertEquals(TraceOutcome.RETURNED, events.last().outcome)
        assertEquals(events.last().spanId, events[1].parentSpanId)
        assertFalse(events.first().toJson().toString().contains("sensitive input"))
    }

    @Test
    fun `cancellation closes nested spans and preserves exact exception`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        val original = CancellationException("private cancellation")

        val caught = assertFailsWith<CancellationException> {
            trace.invocation("find_element", "android") {
                trace.span(TraceStage.HTTP_EXCHANGE) { throw original }
            }
        }

        assertSame(original, caught)
        assertEquals(2, events.size)
        assertTrue(events.all { it.outcome == TraceOutcome.CANCELLED })
    }

    @Test
    fun `timeouts are separate from ordinary cancellation`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        assertFailsWith<CancellationException> {
            trace.invocation("wait_for_element", "android") {
                trace.span(TraceStage.POLL) { withTimeout(1) { delay(2) } }
            }
        }
        val original = TimeoutException("private timeout")
        assertSame(original, assertFailsWith<TimeoutException> {
            trace.invocation("wait_until_gone", "ios") { throw original }
        })
        assertEquals(3, events.size)
        assertTrue(events.all { it.outcome == TraceOutcome.TIMEOUT })
    }

    @Test
    fun `disabled and uncorrelated spans execute without tracing`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        val result = trace.span(TraceStage.HTTP_EXCHANGE) {
            trace.metric(TraceMetric.REQUEST_BYTES, 10L)
            trace.operationOutcome(OperationOutcome.FAILURE)
            42
        }
        assertEquals(42, result)
        assertTrue(events.isEmpty())
        assertEquals(43, TraceRecorder.Disabled.invocation("get_device_info", "android") {
            TraceRecorder.Disabled.span(TraceStage.OPERATION) {
                TraceRecorder.Disabled.metric(TraceMetric.POLL_COUNT, 1L)
                TraceRecorder.Disabled.operationOutcome(OperationOutcome.SUCCESS)
                43
            }
        })
        val original = IOException("private")
        assertSame(original, assertFailsWith<IOException> {
            TraceRecorder.Disabled.invocation("get_device_info", "ios") { throw original }
        })
    }

    @Test
    fun `unknown operation and platform strings never serialize`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        trace.invocation("arbitrary UI text /private/device", "private serial") { Unit }
        val event = events.single()
        assertEquals("other", event.operation)
        assertNull(event.platform)
        val json = Json.parseToJsonElement(event.toJson().toString()).jsonObject
        assertEquals("other", json.getValue("operation").jsonPrimitive.content)
        assertFalse(json.toString().contains("private"))
        assertEquals("host", json.getValue("process").jsonPrimitive.content)
        assertEquals("invocation", json.getValue("stage").jsonPrimitive.content)
        assertEquals("returned", json.getValue("outcome").jsonPrimitive.content)
        assertEquals("unknown", json.getValue("operationOutcome").jsonPrimitive.content)
    }

    @Test
    fun `metrics and authoritative failures serialize separately from returned outcome`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        trace.invocation("find_element", null) {
            trace.metric(TraceMetric.REQUEST_BYTES, 4L)
            trace.metric(TraceMetric.RESPONSE_BYTES, 8L)
            trace.metric(TraceMetric.POLL_COUNT, 1L)
            trace.metric(TraceMetric.WAIT_NS, 5L)
            trace.operationOutcome(OperationOutcome.FAILURE)
            false
        }
        val event = events.single()
        assertEquals(TraceOutcome.RETURNED, event.outcome)
        assertEquals(OperationOutcome.FAILURE, event.operationOutcome)
        assertNull(event.errorCategory)
        val metrics = event.toJson().getValue("metrics").jsonObject
        assertEquals(setOf("requestBytes", "responseBytes", "pollCount", "waitNs"), metrics.keys)
        assertEquals("4", metrics.getValue("requestBytes").jsonPrimitive.content)
    }

    @Test
    fun `returning element absence does not infer failure`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        assertFalse(trace.invocation("find_element", "android") { false })
        assertEquals(OperationOutcome.UNKNOWN, events.single().operationOutcome)
    }

    @Test
    fun `callback failures cannot replace success errors or cancellation`() = runTest {
        val callbackErrors = listOf(IOException("sink"), CancellationException("sink"), AssertionError("sink"))
        callbackErrors.forEach { sinkError ->
            val trace = TraceRecorder(nowNs = { 0L }, emit = { throw sinkError })
            assertEquals(42, trace.invocation("get_device_info", "android") { 42 })
            val original = IOException("operation")
            assertSame(original, assertFailsWith<IOException> {
                trace.invocation("get_device_info", "android") { throw original }
            })
            val cancelled = CancellationException("operation")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                trace.invocation("get_device_info", "android") { throw cancelled }
            })
        }
    }

    @Test
    fun `callback failure does not hide cancellation of the callers job`() = runTest {
        val invocation = async {
            val caller = checkNotNull(currentCoroutineContext()[Job])
            val trace = TraceRecorder(nowNs = { 0L }, emit = {
                caller.cancel(CancellationException("private cancellation"))
                throw IOException("private sink failure")
            })
            trace.invocation("get_device_info", "android") { 42 }
        }
        assertFailsWith<CancellationException> { invocation.await() }
        assertTrue(invocation.isCancelled)
    }

    @Test
    fun `regressing clock and overflowing metrics remain nonnegative`() = runTest {
        var now = 10L
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { now }, emit = { events.add(it); Unit })
        now = 5L
        trace.invocation("get_device_info", "android") {
            trace.metric(TraceMetric.REQUEST_BYTES, -1L)
            trace.metric(TraceMetric.POLL_COUNT, Long.MAX_VALUE)
            trace.metric(TraceMetric.POLL_COUNT, 1L)
            now = 0L
        }
        assertEquals(0L, events.single().startOffsetNs)
        assertEquals(0L, events.single().durationNs)
        assertNull(events.single().metrics[TraceMetric.REQUEST_BYTES])
        assertEquals(Long.MAX_VALUE, events.single().metrics[TraceMetric.POLL_COUNT])
    }

    @Test
    fun `different recorders do not inherit each others active spans`() = runTest {
        val firstEvents = mutableListOf<TraceEvent>()
        val secondEvents = mutableListOf<TraceEvent>()
        val first = TraceRecorder(nowNs = { 0L }, emit = { firstEvents.add(it); Unit })
        val second = TraceRecorder(nowNs = { 0L }, emit = { secondEvents.add(it); Unit })
        first.invocation("get_device_info", "android") {
            second.span(TraceStage.HEALTH) { Unit }
            second.invocation("ios_get_device_info", "ios") { Unit }
            first.span(TraceStage.HEALTH) { Unit }
        }
        assertEquals(2, firstEvents.size)
        assertEquals(1, secondEvents.size)
        assertNull(secondEvents.single().parentSpanId)
        assertEquals(firstEvents.last().spanId, firstEvents.first().parentSpanId)
        assertFalse(firstEvents.last().sessionId == secondEvents.single().sessionId)
    }

    @Test
    fun `error categories are fixed and preserve original errors`() = runTest {
        val examples = listOf(
            IllegalArgumentException("private") to TraceErrorCategory.VALIDATION,
            ServerNotRunningException("private") to TraceErrorCategory.UNREACHABLE,
            NoDeviceAvailableException("private") to TraceErrorCategory.DEVICE_MISSING,
            NoSimulatorAvailableException("private") to TraceErrorCategory.DEVICE_MISSING,
            CliExit(ExitCode.PlatformNotSupported, "private") to TraceErrorCategory.UNSUPPORTED_PLATFORM,
            JsonParseException("private") to TraceErrorCategory.PROTOCOL,
            ConnectException("private") to TraceErrorCategory.TRANSPORT,
            IOException("private") to TraceErrorCategory.IO,
            AssertionError("private") to TraceErrorCategory.OTHER,
        )
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(nowNs = { 0L }, emit = { events.add(it); Unit })
        examples.forEach { (original, category) ->
            assertSame(original, assertFailsWith<Throwable> {
                trace.invocation("get_device_info", "android") { throw original }
            })
            assertEquals(category, events.last().errorCategory)
            assertFalse(events.last().toJson().toString().contains("private"))
        }
    }
    @Test
    fun `seal freezes admission counts while original operations continue`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val recorder = TraceRecorder(emit = { events.add(it); Unit })
        recorder.invocation("init", null) { "first" }
        var cutoff: TraceInvocationSnapshot? = null
        assertEquals("active result", recorder.invocation("init", null) {
            cutoff = recorder.seal()
            "active result"
        })
        assertEquals(TraceInvocationSnapshot(2, 1), cutoff)
        assertEquals(cutoff, recorder.seal())
        assertEquals("late result", recorder.invocation("init", null) { "late result" })
        val original = IllegalArgumentException("private")
        assertSame(original, assertFailsWith<IllegalArgumentException> {
            recorder.invocation("init", null) { throw original }
        })
        assertEquals(2, events.size)
        assertEquals(cutoff, recorder.seal())
    }

    @Test
    fun `disabled sealing and invocation never read clock`() = runTest {
        var clockReads = 0
        val recorder = TraceRecorder(nowNs = { clockReads++; 1L }, emit = {}, enabled = false)
        assertEquals(TraceInvocationSnapshot(0, 0), recorder.seal())
        assertEquals("result", recorder.invocation("init", null) { "result" })
        assertEquals(0, clockReads)
    }

}
