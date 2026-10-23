package dev.visiontest.recording

import dev.visiontest.common.RequestDeadline
import dev.visiontest.NoSimulatorAvailableException
import dev.visiontest.ios.parseIosSimulatorDevices
import dev.visiontest.ios.selectFirstAvailableSimulator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal class IosRecordingSession(
    private val scope: CoroutineScope,
    private val artifacts: Path,
    private val processes: RecordingProcessRunner,
    private val inspector: RecordingContainerInspector = RecordingContainerInspector(),
) : RecordingSession {
    override val terminal = CompletableDeferred<RecordingExit>()
    private var process: OwnedRecordingProcess? = null
    private var temporary: Path? = null
    private val stopping = AtomicBoolean(false)
    private val finalization = Mutex()
    private var outcome: Result<Path>? = null

    override suspend fun start(deadline: RequestDeadline) = recordingOperation {
        val discovery = bounded(deadline) {
            processes.run(listOf("xcrun", "simctl", "list", "devices", "available", "--json"), deadline)
        }
        if (discovery.exitCode != 0) throw RecordingException("Unable to discover iOS simulators: ${discovery.stderr}")
        val device = selectFirstAvailableSimulator(parseIosSimulatorDevices(discovery.stdout.decodeToString()))
        if (device.id.isBlank()) throw RecordingException("Selected iOS simulator has a blank UDID")
        if (device.state != "Booted") throw RecordingException("Selected iOS simulator ${device.id} is not booted")
        val path = withContext(Dispatchers.IO) {
            deadline.remainingMillis()
            Files.createDirectories(artifacts)
            Files.createTempFile(artifacts.toAbsolutePath(), ".recording-", ".mov").also {
                temporary = it
                Files.delete(it)
            }
        }
        val handle = processes.start(scope,
            listOf("xcrun", "simctl", "io", device.id, "recordVideo", "--codec=h264", path.toString()),
            deadline, "Recording started")
        process = handle
        observeExit(handle)
        bounded(deadline) {
            select {
                handle.exit.onAwait { throw RecordingException("iOS recorder exited before readiness: ${it.stderr}") }
                handle.stderrReady.onAwait { }
            }
        }
        if (!handle.isAlive() || handle.exit.isCompleted) throw RecordingException("iOS recorder exited during startup")
    }

    override suspend fun finish(deadline: RequestDeadline): Path = withContext(NonCancellable) {
        finalization.withLock {
            outcome?.let { return@withLock it.getOrThrow() }
            var result = runCatching { recordingOperation { finalizeVideo(deadline) } }
            val cleanup = runCatching { recordingOperation { cleanup(deadline) } }.exceptionOrNull()
            if (cleanup != null) {
                val failure = result.exceptionOrNull()
                if (failure != null) failure.addSuppressed(cleanup) else result = Result.failure(cleanup)
            }
            outcome = result
            result.getOrThrow()
        }
    }

    private suspend fun finalizeVideo(deadline: RequestDeadline): Path {
        val handle = process ?: throw RecordingException("iOS recorder did not start")
        val exitedBeforeStop = !handle.isAlive() || handle.exit.isCompleted
        stopping.set(true)
        val premature = exitedBeforeStop || !handle.isAlive() || handle.exit.isCompleted
        val exit = stop(handle, premature, deadline)
        if (premature) terminal.complete(RecordingExit(exit.exitCode, exit.stderr))
        else if (exit.exitCode != 0) throw RecordingException("iOS recorder failed to finalize: ${exit.stderr}")
        val path = checkNotNull(temporary)
        inspector.validateVideo(path, deadline)
        return withContext(Dispatchers.IO) {
            deadline.remainingMillis()
            Files.move(path, artifacts.toAbsolutePath().resolve("recording.mov"))
        }
    }

    private suspend fun stop(
        handle: OwnedRecordingProcess,
        premature: Boolean,
        deadline: RequestDeadline,
    ): RecordingCommandResult {
        val remaining = deadline.remainingMillis()
        val stopBudget = (remaining - minOf(CLEANUP_RESERVE_MS, remaining / CLEANUP_RESERVE_FRACTION)).coerceAtLeast(1)
        return withTimeoutOrNull(stopBudget) {
            if (!premature) handle.interrupt(deadline)
            handle.exit.await()
        } ?: throw RecordingException("iOS recorder did not stop before the finalization deadline")
    }

    private suspend fun cleanup(deadline: RequestDeadline) {
        try {
            process?.takeIf { it.isAlive() }?.let { handle -> bounded(deadline) { handle.forceStop(deadline) } }
        } finally {
            removeTemporary(deadline)
        }
    }

    private suspend fun removeTemporary(deadline: RequestDeadline) = withContext(Dispatchers.IO) {
        temporary?.let {
            deadline.remainingMillis()
            Files.deleteIfExists(it)
        }
        Unit
    }

    @Suppress("TooGenericExceptionCaught")
    private fun observeExit(handle: OwnedRecordingProcess) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                val exit = handle.exit.await()
                RecordingExit(exit.exitCode, exit.stderr)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RecordingExit(null, failure.message ?: "Unable to observe iOS recorder exit")
            }
            if (!stopping.get()) terminal.complete(result)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> recordingOperation(block: suspend () -> T): T = try {
        block()
    } catch (failure: Exception) {
        throw when (failure) {
            is CancellationException, is NoSimulatorAvailableException, is RecordingException -> failure
            else -> RecordingException("iOS recording failed: ${failure.message}", failure)
        }
    }

    private suspend fun <T : Any> bounded(deadline: RequestDeadline, block: suspend () -> T): T =
        withTimeoutOrNull(deadline.remainingMillis()) { block() }
            ?: throw RecordingException("iOS recording deadline exceeded")

    private companion object {
        const val CLEANUP_RESERVE_MS = 1000L
        const val CLEANUP_RESERVE_FRACTION = 5
    }
}
