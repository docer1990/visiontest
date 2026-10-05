package com.example.visiontest.performance

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.NoSimulatorAvailableException
import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.cli.CliExit
import com.example.visiontest.cli.ExitCode
import com.github.ajalt.clikt.core.CliktError
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class TraceRecorder(
    private val nowNs: () -> Long = System::nanoTime,
    private val emit: (TraceEvent) -> Unit,
    private val enabled: Boolean = true,
) {
    val sessionId: String = newId()
    val originNs: Long = if (enabled) nowNs() else 0L
    private val invocationSequence = AtomicLong()

    suspend fun <T> invocation(operation: String, platform: String?, block: suspend () -> T): T {
        if (!enabled) return block()
        val invocation = Invocation(newId(), invocationSequence.incrementAndGet(),
            TraceNames.operation(operation), TraceNames.platform(platform))
        return record(SpanContext(this, invocation, newId(), null), TraceStage.INVOCATION, block)
    }

    suspend fun <T> span(stage: TraceStage, block: suspend () -> T): T {
        val parent = activeContext() ?: return block()
        return record(SpanContext(this, parent.invocation, newId(), parent.spanId), stage, block)
    }

    suspend fun metric(name: TraceMetric, value: Long) {
        if (value < 0L) return
        activeContext()?.metrics?.computeIfAbsent(name) { AtomicLong() }?.updateAndGet {
            if (Long.MAX_VALUE - it < value) Long.MAX_VALUE else it + value
        }
    }

    suspend fun operationOutcome(value: OperationOutcome) {
        activeContext()?.operationOutcome?.set(value)
    }

    private suspend fun activeContext(): SpanContext? =
        coroutineContext[SpanContext]?.takeIf { enabled && it.recorder === this }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> record(context: SpanContext, stage: TraceStage, block: suspend () -> T): T {
        val start = nowNs()
        var failure: Throwable? = null
        try {
            return withContext(context) {
                try {
                    block()
                } catch (error: Throwable) {
                    failure = error
                    throw error
                }
            }
        } catch (error: Throwable) {
            // Coroutine stack-trace recovery can copy the exception at the context boundary.
            val original = failure ?: error
            failure = original
            throw original
        } finally {
            val event = event(context, stage, start, nowNs(), failure)
            runCatching { emit(event) }
            if (failure == null) coroutineContext.ensureActive()
        }
    }

    private fun event(
        context: SpanContext,
        stage: TraceStage,
        start: Long,
        end: Long,
        failure: Throwable?,
    ): TraceEvent =
        TraceEvent(
            sessionId = sessionId,
            invocationId = context.invocation.id,
            invocationSequence = context.invocation.sequence,
            spanId = context.spanId,
            parentSpanId = context.parentSpanId,
            platform = context.invocation.platform,
            operation = context.invocation.operation,
            stage = stage,
            startOffsetNs = (start - originNs).coerceAtLeast(0L),
            durationNs = (end - start).coerceAtLeast(0L),
            outcome = outcome(failure),
            operationOutcome = context.operationOutcome.get(),
            metrics = context.metrics.mapValues { it.value.get() },
            errorCategory = category(failure),
        )

    private data class Invocation(val id: String, val sequence: Long, val operation: String, val platform: String?)

    private class SpanContext(
        val recorder: TraceRecorder,
        val invocation: Invocation,
        val spanId: String,
        val parentSpanId: String?,
    ) : AbstractCoroutineContextElement(Key) {
        val metrics = ConcurrentHashMap<TraceMetric, AtomicLong>()
        val operationOutcome = AtomicReference(OperationOutcome.UNKNOWN)

        companion object Key : CoroutineContext.Key<SpanContext>
    }

    companion object {
        val Disabled = TraceRecorder(nowNs = { 0L }, emit = {}, enabled = false)

        private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

        private fun outcome(error: Throwable?): TraceOutcome = when (error) {
            null -> TraceOutcome.RETURNED
            is TimeoutCancellationException, is TimeoutException, is SocketTimeoutException -> TraceOutcome.TIMEOUT
            is CancellationException -> TraceOutcome.CANCELLED
            else -> TraceOutcome.THROWN
        }

        private fun category(error: Throwable?): TraceErrorCategory? = when (error) {
            null, is CancellationException, is TimeoutException -> null
            is CliExit -> cliCategory(error.code)
            is IllegalArgumentException, is CliktError -> TraceErrorCategory.VALIDATION
            is ServerNotRunningException -> TraceErrorCategory.UNREACHABLE
            is NoDeviceAvailableException, is NoSimulatorAvailableException -> TraceErrorCategory.DEVICE_MISSING
            is JsonParseException, is SerializationException -> TraceErrorCategory.PROTOCOL
            is SocketException, is SocketTimeoutException -> TraceErrorCategory.TRANSPORT
            is IOException -> TraceErrorCategory.IO
            else -> TraceErrorCategory.OTHER
        }

        private fun cliCategory(code: ExitCode): TraceErrorCategory = when (code) {
            ExitCode.UsageError -> TraceErrorCategory.VALIDATION
            ExitCode.ServerNotReachable -> TraceErrorCategory.UNREACHABLE
            ExitCode.DeviceNotFound -> TraceErrorCategory.DEVICE_MISSING
            ExitCode.PlatformNotSupported -> TraceErrorCategory.UNSUPPORTED_PLATFORM
            else -> TraceErrorCategory.OTHER
        }
    }
}
