package dev.visiontest.recording

import dev.visiontest.common.RequestDeadline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface OwnedRecordingProcess {
    val pid: Long
    val firstStdoutLine: Deferred<String>
    val stderrReady: Deferred<Unit>
    val exit: Deferred<RecordingCommandResult>
    fun isAlive(): Boolean
    suspend fun interrupt(deadline: RequestDeadline)
    suspend fun forceStop(deadline: RequestDeadline)
}

internal class JvmOwnedRecordingProcess(
    scope: CoroutineScope,
    private val process: Process,
    private val stderrReadyLine: String?,
    private val stdoutLimitBytes: Int? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OwnedRecordingProcess {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + lifetime + dispatcher)
    override val pid: Long = process.pid()
    override val firstStdoutLine = CompletableDeferred<String>()
    override val stderrReady = CompletableDeferred<Unit>().also { if (stderrReadyLine == null) it.complete(Unit) }
    private val termination = Mutex()
    private var interrupted = false
    private val stdout = workers.async { readAsync(process.inputStream, true) }
    private val stderr = workers.async { readAsync(process.errorStream, false) }
    override val exit = workers.async {
        coroutineScope {
            val output = async { stdout.await() }
            val diagnostic = async { stderr.await() }
            awaitProcessExit()
            withTimeoutOrNull(READER_SHUTDOWN_MS) {
                RecordingCommandResult(process.exitValue(), output.await(), diagnostic.await().decodeToString())
            } ?: throw RecordingException("Recording output readers did not finish after process exit")
        }
    }

    init {
        exit.invokeOnCompletion { cause ->
            stdout.cancel()
            stderr.cancel()
            val failure = cause ?: RecordingException("Process exited before readiness")
            firstStdoutLine.completeExceptionally(failure)
            stderrReady.completeExceptionally(failure)
            lifetime.complete()
        }
    }

    override fun isAlive(): Boolean = process.isAlive

    override suspend fun interrupt(deadline: RequestDeadline) = terminate(deadline) {
        if (process.isAlive && !interrupted) {
            if (File.separatorChar != '/') throw RecordingException("Recording interrupt requires Unix signals")
            val signal = JvmRecordingProcessRunner().run(listOf("/bin/kill", "-INT", pid.toString()), deadline)
            if (signal.exitCode != 0 && process.isAlive) {
                throw RecordingException("Unable to interrupt recording process: ${signal.stderr}")
            }
            interrupted = true
        }
    }

    override suspend fun forceStop(deadline: RequestDeadline) = withContext(dispatcher) {
        if (process.isAlive) process.destroyForcibly()
        terminate(deadline) { }
    }

    private suspend fun terminate(deadline: RequestDeadline, signal: suspend () -> Unit) {
        withContext(dispatcher) {
            withTimeoutOrNull(deadline.remainingMillis()) {
                termination.withLock {
                    signal()
                    awaitProcessExit()
                    exit.join()
                }
                true
            } ?: throw TimeoutException("Recording process did not stop")
        }
    }

    private suspend fun awaitProcessExit(): Unit = suspendCancellableCoroutine { continuation ->
        process.onExit().whenComplete { _, failure ->
            if (failure == null) continuation.resume(Unit) else continuation.resumeWithException(failure)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Reader failures must resume the coroutine instead of escaping its worker.
    private suspend fun readAsync(stream: InputStream, isStdout: Boolean): ByteArray =
        suspendCancellableCoroutine { continuation ->
            // Process pipe reads and close can ignore interruption. Daemon workers keep shutdown bounded.
            val reader = Thread({
                try {
                    continuation.resume(drain(stream, isStdout))
                } catch (failure: Exception) {
                    val reported = failure as? RecordingException
                        ?: RecordingException("Unable to read recording process output", failure)
                    if (isStdout) firstStdoutLine.completeExceptionally(reported)
                    else stderrReady.completeExceptionally(reported)
                    continuation.resumeWithException(reported)
                }
            }, "recording-$pid-reader").apply { isDaemon = true }
            continuation.invokeOnCancellation {
                reader.interrupt()
                Thread({
                    try { stream.close() } catch (_: IOException) { Unit }
                }, "recording-$pid-close").apply { isDaemon = true }.start()
            }
            reader.start()
        }

    private fun drain(stream: InputStream, isStdout: Boolean): ByteArray {
        val capture = ByteArrayOutputStream()
        val line = ByteArrayOutputStream()
        val limit = if (isStdout) stdoutLimitBytes ?: DIAGNOSTIC_LIMIT else DIAGNOSTIC_LIMIT
        stream.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = it.read(buffer)
                if (count == -1) break
                if (isStdout && stdoutLimitBytes != null && count > limit - capture.size()) {
                    throw RecordingException("Command stdout exceeds $limit byte limit")
                }
                capture.write(buffer, 0, minOf(count, limit - capture.size()))
                inspectLines(buffer, count, line, isStdout)
            }
        }
        if (line.size() > 0) acceptLine(line.toByteArray().decodeToString(), isStdout)
        if (isStdout) firstStdoutLine.completeExceptionally(RecordingException("EOF before stdout readiness"))
        else if (stderrReadyLine == null) stderrReady.complete(Unit)
        else stderrReady.completeExceptionally(RecordingException("EOF before stderr readiness"))
        return capture.toByteArray()
    }

    private fun inspectLines(buffer: ByteArray, count: Int, line: ByteArrayOutputStream, isStdout: Boolean) {
        for (index in 0 until count) {
            if (!needsLine(isStdout)) break
            if (buffer[index] == '\n'.code.toByte()) {
                acceptLine(line.toByteArray().decodeToString().removeSuffix("\r"), isStdout)
                line.reset()
            } else {
                if (line.size() == DIAGNOSTIC_LIMIT) {
                    throw RecordingException("Readiness line exceeds $DIAGNOSTIC_LIMIT byte limit")
                }
                line.write(buffer[index].toInt())
            }
        }
    }

    private fun needsLine(isStdout: Boolean): Boolean = stdoutLimitBytes == null &&
        if (isStdout) !firstStdoutLine.isCompleted else stderrReadyLine != null && !stderrReady.isCompleted

    private fun acceptLine(line: String, isStdout: Boolean) {
        if (isStdout) firstStdoutLine.complete(line)
        else if (line == stderrReadyLine) stderrReady.complete(Unit)
    }

    private companion object {
        const val DIAGNOSTIC_LIMIT = 65_536
        const val READER_SHUTDOWN_MS = 250L
    }
}
