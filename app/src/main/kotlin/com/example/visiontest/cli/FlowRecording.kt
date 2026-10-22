package com.example.visiontest.cli

import com.example.visiontest.recording.AndroidRecordingSession
import com.example.visiontest.recording.IosRecordingSession
import com.example.visiontest.recording.JvmRecordingProcessRunner
import com.example.visiontest.recording.RecordingSession
import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class FlowRecordingReport(
    val status: String,
    val phase: String? = null,
    val path: String? = null,
    val error: FlowError? = null,
)

internal fun interface FlowRecordingFactory {
    fun create(scope: CoroutineScope, platform: Platform, artifacts: Path, requiredDurationMs: Long): RecordingSession
}

internal data class FlowRecordingOptions(
    val artifacts: Path,
    val factory: Lazy<FlowRecordingFactory>,
    val registerShutdown: (suspend () -> Unit) -> AutoCloseable = ::registerRecordingShutdown,
)

internal class FlowRecordingLifetime(
    private val options: FlowRecordingOptions,
    private val owner: CoroutineScope,
    private val platform: Platform,
    private val requiredDurationMs: Long,
    private val deadlineFactory: (Long) -> RequestDeadline,
) : RecordingSession {
    private val workers = SupervisorJob()
    private val scope = CoroutineScope(owner.coroutineContext + workers)
    private val lifecycle = Mutex()
    private var session: RecordingSession? = null
    private var registration: AutoCloseable? = null
    private var outcome: Result<Path>? = null
    private var finalizationDeadline: RequestDeadline? = null
    override val terminal get() = checkNotNull(session).terminal

    override suspend fun start(deadline: RequestDeadline) = lifecycle.withLock {
        registration = options.registerShutdown {
            owner.cancel(CancellationException("JVM shutdown"))
            runCatching { finish(deadlineFactory(FINALIZATION_TIMEOUT_MS)) }
            Unit
        }
        val allocated = options.factory.value.create(scope, platform, options.artifacts, requiredDurationMs)
        session = allocated
        allocated.start(deadline)
    }

    override suspend fun finish(deadline: RequestDeadline): Path = withContext(NonCancellable) {
        val shared = synchronized(this@FlowRecordingLifetime) {
            finalizationDeadline ?: deadline.also { finalizationDeadline = it }
        }
        lifecycle.withLock {
            outcome?.let { return@withLock it.getOrThrow() }
            val result = runCatching {
                withTimeoutOrNull(shared.remainingMillis()) {
                    checkNotNull(session) { "Recording session was not created" }.finish(shared)
                        .also { shared.remainingMillis() }
                } ?: throw TimeoutException("Flow recording finalization deadline exceeded")
            }
            outcome = result
            workers.cancelAndJoin()
            registration?.close()
            registration = null
            result.getOrThrow()
        }
    }

    private companion object {
        const val FINALIZATION_TIMEOUT_MS = 30000L
    }
}

private fun registerRecordingShutdown(cleanup: suspend () -> Unit): AutoCloseable {
    val hook = Thread({
        val completed = CountDownLatch(1)
        val task = CoroutineScope(Dispatchers.IO).launch {
            try { cleanup() } finally { completed.countDown() }
        }
        try { completed.await(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) } finally { task.cancel() }
    }, "flow-recording-shutdown")
    Runtime.getRuntime().addShutdownHook(hook)
    return AutoCloseable {
        try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) {
            // Hooks cannot be removed once JVM shutdown has begun.
        }
    }
}

private const val SHUTDOWN_TIMEOUT_MS = 30000L

internal class SystemFlowRecordingFactory : FlowRecordingFactory {
    override fun create(
        scope: CoroutineScope,
        platform: Platform,
        artifacts: Path,
        requiredDurationMs: Long,
    ): RecordingSession = when (platform) {
        Platform.Android -> AndroidRecordingSession(scope, artifacts, requiredDurationMs, JvmRecordingProcessRunner())
        Platform.Ios -> IosRecordingSession(scope, artifacts, JvmRecordingProcessRunner())
    }
}
