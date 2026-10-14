package com.example.automationserver.performance

import com.google.gson.GsonBuilder
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class NativeTimingSpan(
    val spanId: String,
    val parentSpanId: String?,
    val stage: String,
    val startOffsetNs: Long,
    val durationNs: Long,
    val outcome: String,
    val metrics: Map<String, Long> = emptyMap(),
)

data class NativeTimingSnapshot(val spans: List<NativeTimingSpan>, val missingStages: List<String>)

class NativeTimingRecorder(
    private val invocationId: String,
    private val requestSpanId: String,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val origin = readClock()
    private val spans = mutableListOf<NativeTimingSpan>()
    // SDK sync and disjoint poll delays have no separately exposed continuous interval.
    private val missing = linkedSetOf("queue_wait", "ui_sync", "poll_wait")
    private var admitted = 0
    private val active = mutableMapOf<String, String>()

    private class Scope(val recorder: NativeTimingRecorder, val id: String, val stage: String) {
        val metrics = mutableMapOf<String, Long>()
    }
    private data class Started(val scope: Scope, val parent: String?, val start: Long)

    @Suppress("TooGenericExceptionCaught")
    fun <T> measure(stage: String, block: () -> T): T {
        val started = begin(stage) ?: return block()
        val previous = current.get()
        current.set(started.scope)
        var outcome = "returned"
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = outcome(failure)
            throw failure
        } finally {
            current.set(previous)
            finish(started, outcome)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> measureSuspending(stage: String, block: suspend () -> T): T {
        val started = begin(stage) ?: return block()
        var outcome = "returned"
        try {
            return withContext(current.asContextElement(started.scope)) { block() }
        } catch (failure: Throwable) {
            outcome = outcome(failure)
            throw failure
        } finally {
            finish(started, outcome)
        }
    }

    @Synchronized
    fun snapshot(): NativeTimingSnapshot {
        val ids = spans.map { it.spanId }.toSet()
        return NativeTimingSnapshot(
            Collections.unmodifiableList(spans.map { span ->
                span.copy(parentSpanId = span.parentSpanId?.takeIf { it in ids },
                    metrics = Collections.unmodifiableMap(span.metrics.toMap()))
            }),
            Collections.unmodifiableList((missing + active.values).distinct()),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Suppress("TooGenericExceptionCaught")
    fun encodeHeader(): String? = try {
        var snapshot = snapshot()
        var encoded: String
        do {
            val envelope = mapOf("version" to 1, "invocationId" to invocationId,
                "requestSpanId" to requestSpanId, "spans" to snapshot.spans, "missingStages" to snapshot.missingStages)
            val bytes = GsonBuilder().serializeNulls().create().toJson(envelope).toByteArray(Charsets.UTF_8)
            encoded = Base64.UrlSafe.encode(bytes)
            if (encoded.length > MAX_HEADER && snapshot.spans.isNotEmpty()) {
                val removed = snapshot.spans.last()
                val retained = snapshot.spans.dropLast(1)
                val ids = retained.map { it.spanId }.toSet()
                val roots = retained.map { it.copy(parentSpanId = it.parentSpanId?.takeIf(ids::contains)) }
                snapshot = NativeTimingSnapshot(roots,
                    (snapshot.missingStages + removed.stage).distinct())
            } else break
        } while (true)
        encoded.takeIf { it.length <= MAX_HEADER }
    } catch (_: Throwable) { null }

    @Synchronized
    private fun begin(stage: String): Started? {
        if (stage !in STAGES) return null
        val start = if (origin != null && admitted < MAX_SPANS) readClock() else null
        return if (start == null) {
            missing.add(stage)
            null
        } else {
            admitted++
            val parent = current.get()?.takeIf { it.recorder === this }?.id
            val scope = Scope(this, UUID.randomUUID().toString().replace("-", ""), stage)
            active[scope.id] = stage
            Started(scope, parent, offset(start))
        }
    }

    @Synchronized
    private fun finish(started: Started, outcome: String) {
        active.remove(started.scope.id)
        val end = readClock() ?: run { missing.add(started.scope.stage); return }
        val duration = (offset(end) - started.start).coerceAtLeast(0L)
        spans.add(NativeTimingSpan(started.scope.id, started.parent, started.scope.stage,
            started.start, duration, outcome, started.scope.metrics.toMap()))
    }

    private fun readClock(): Long? = runCatching { nowNs() }.getOrNull()
    private fun offset(value: Long): Long = (value - (origin ?: value)).coerceAtLeast(0L)

    companion object {
        private const val MAX_SPANS = 32
        private const val MAX_HEADER = 8192
        private val current = ThreadLocal<Scope?>()
        private val METRICS = setOf("requestBytes", "responseBytes", "decodedBytes", "pollCount", "waitNs")
        private val HEADER = Regex("v1;([0-9a-f]{32});([0-9a-f]{32})")
        private val STAGES = setOf("request", "decode", "queue_wait", "operation", "ui_sync", "poll",
            "poll_wait", "screenshot_capture", "png_encode", "base64_encode", "response_encode",
            "screenshot_capture_encode")

        fun fromHeader(header: String?): NativeTimingRecorder? = header?.let { HEADER.matchEntire(it) }?.let {
            NativeTimingRecorder(it.groupValues[1], it.groupValues[2])
        }

        fun <T> measureCurrent(stage: String, block: () -> T): T {
            val recorder = current.get()?.recorder ?: return block()
            return recorder.measure(stage, block)
        }

        fun <T> pollAttempt(block: () -> T): T {
            metric("pollCount", 1)
            return block()
        }

        fun <T> pollWait(block: () -> T): T {
            val recorder = current.get()?.recorder ?: return block()
            val start = recorder.readClock()
            try { return block() } finally {
                val end = recorder.readClock()
                if (start != null && end != null) metric("waitNs", (end - start).coerceAtLeast(0L))
            }
        }

        fun metric(name: String, value: Long) {
            val scope = current.get() ?: return
            if (name !in METRICS || value < 0) return
            synchronized(scope.recorder) {
                val old = scope.metrics[name] ?: 0L
                scope.metrics[name] = if (Long.MAX_VALUE - old < value) Long.MAX_VALUE else old + value
            }
        }

        private fun outcome(failure: Throwable): String = when (failure) {
            is kotlinx.coroutines.TimeoutCancellationException, is TimeoutException,
            is SocketTimeoutException -> "timeout"
            is CancellationException -> "cancelled"
            else -> "thrown"
        }
    }
}
