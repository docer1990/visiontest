package com.example.automationserver.performance

import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class NativeTimingRecorderTest {
    @Test fun `injected clock records duration and header correlation`() {
        var now = 100L
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32)) { now }
        assertEquals(7, recorder.measure("operation") { now = 140L; 7 })
        assertEquals(40L, recorder.snapshot().spans.single().durationNs)
        val bytes = Base64.getUrlDecoder().decode(recorder.encodeHeader())
        val json = JsonParser.parseString(String(bytes)).asJsonObject
        assertEquals("a".repeat(32), json["invocationId"].asString)
        assertEquals(1, json["version"].asInt)
    }

    @Test fun `failed origin clock leaves stages missing without fabricated timings`() {
        var calls = 0
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32)) {
            if (calls++ == 0) error("clock") else 10L
        }
        assertEquals(7, recorder.measure("operation") { 7 })
        assertTrue(recorder.snapshot().spans.isEmpty())
        assertTrue("operation" in recorder.snapshot().missingStages)
    }

    @Test fun `completed stage is not reported as missing`() {
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32))
        recorder.measure("ui_sync") {}
        assertTrue(recorder.snapshot().spans.any { it.stage == "ui_sync" })
        assertFalse("ui_sync" in recorder.snapshot().missingStages)
    }

    @Test fun `unfinished parent is omitted and completed child becomes root`() {
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32))
        recorder.measure("request") {
            recorder.measure("decode") {}
            assertNull(recorder.snapshot().spans.single().parentSpanId)
            assertTrue("request" in recorder.snapshot().missingStages)
        }
        assertEquals(2, recorder.snapshot().spans.size)
    }

    @Test fun `clock errors cannot replace an operation throwable`() {
        var calls = 0
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32)) {
            if (calls++ >= 2) throw AssertionError("clock") else calls.toLong()
        }
        val original = IllegalArgumentException("operation")
        val thrown = assertFailsWith<IllegalArgumentException> {
            recorder.measure("operation") { throw original }
        }
        assertSame(original, thrown)
        assertTrue("operation" in recorder.snapshot().missingStages)
    }

    @Test fun `invalid request headers disable recording`() {
        listOf(null, "", "v2;${"a".repeat(32)};${"b".repeat(32)}",
            "v1;secret;secret", "v1;${"A".repeat(32)};${"b".repeat(32)}")
            .forEach { assertNull(NativeTimingRecorder.fromHeader(it)) }
    }

    @Test fun `bounded spans preserve original throwable and report omitted stages`() {
        var now = 0L
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32)) { now++ }
        val original = IllegalStateException("secret")
        val thrown = assertFailsWith<IllegalStateException> { recorder.measure("operation") { throw original } }
        assertSame(original, thrown)
        repeat(60) { recorder.measure("ui_sync") {} }
        assertTrue(recorder.snapshot().spans.size <= 32)
        assertTrue("ui_sync" in recorder.snapshot().missingStages)
        assertTrue(recorder.encodeHeader()!!.length <= 8192)
        assertFalse(recorder.encodeHeader()!!.contains("secret"))
    }

    @Test fun `concurrent suspend requests keep independent parents`() = runBlocking {
        val recorders = List(2) {
            NativeTimingRecorder("a".repeat(32), (if (it == 0) "b" else "c").repeat(32))
        }
        recorders.map { recorder ->
            async { recorder.measureSuspending("request") {
                yield()
                NativeTimingRecorder.measureCurrent("operation") {}
            } }
        }.forEach { it.await() }
        recorders.forEach { recorder ->
            val spans = recorder.snapshot().spans
            val parent = spans.single { it.stage == "request" }.spanId
            assertEquals(parent, spans.single { it.stage == "operation" }.parentSpanId)
        }
    }

    @Test fun `metrics saturate and snapshots remain immutable`() {
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32))
        recorder.measure("poll") {
            NativeTimingRecorder.metric("pollCount", Long.MAX_VALUE)
            NativeTimingRecorder.metric("pollCount", 1)
            NativeTimingRecorder.metric("pollCount", -1)
            NativeTimingRecorder.metric("secret", 1)
        }
        val snapshot = recorder.snapshot()
        assertEquals(mapOf("pollCount" to Long.MAX_VALUE), snapshot.spans.single().metrics)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.spans as MutableList).clear()
        }
        recorder.measure("decode") {}
        assertEquals(1, snapshot.spans.size)
    }

    @Test fun `timeout and cancellation preserve the exact original throwable`() {
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32))
        val failures = listOf(java.net.SocketTimeoutException(), java.util.concurrent.CancellationException())
        failures.forEach { original ->
            val thrown = assertFailsWith<Exception> { recorder.measure("operation") { throw original } }
            assertSame(original, thrown)
        }
        assertEquals(listOf("timeout", "cancelled"), recorder.snapshot().spans.map { it.outcome })
    }

    @Test fun `nullable results are executed once with an active scope`() {
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32))
        var calls = 0
        recorder.measure("request") {
            assertNull(NativeTimingRecorder.measureCurrent("decode") { calls++; null })
        }
        assertEquals(1, calls)
    }

    @Test fun `poll metrics aggregate without extra operations`() {
        var now = 0L
        val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32)) { now }
        recorder.measure("poll") {
            repeat(100) { NativeTimingRecorder.pollAttempt { now++ }; NativeTimingRecorder.pollWait { now += 2 } }
        }
        val span = recorder.snapshot().spans.single { it.stage == "poll" }
        assertEquals(100L, span.metrics["pollCount"])
        assertEquals(200L, span.metrics["waitNs"])
        assertTrue(recorder.snapshot().spans.size <= 3)
    }
}
