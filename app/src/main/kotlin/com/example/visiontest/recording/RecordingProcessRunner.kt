package com.example.visiontest.recording

import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

internal data class RecordingCommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

internal class RecordingException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal interface RecordingProcessRunner {
    suspend fun start(
        scope: CoroutineScope,
        command: List<String>,
        deadline: RequestDeadline,
        stderrReadyLine: String? = null,
    ): OwnedRecordingProcess

    suspend fun run(
        command: List<String>,
        deadline: RequestDeadline,
        stdoutLimitBytes: Int = 1_048_576,
    ): RecordingCommandResult
}

internal class JvmRecordingProcessRunner(
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : RecordingProcessRunner {
    override suspend fun start(
        scope: CoroutineScope,
        command: List<String>,
        deadline: RequestDeadline,
        stderrReadyLine: String?,
    ): OwnedRecordingProcess = execute(command, deadline, { _, process ->
        JvmOwnedRecordingProcess(scope, process, stderrReadyLine)
    }) { it }

    override suspend fun run(
        command: List<String>,
        deadline: RequestDeadline,
        stdoutLimitBytes: Int,
    ): RecordingCommandResult = execute(command, deadline, { scope, process ->
        JvmOwnedRecordingProcess(scope, process, null, stdoutLimitBytes)
    }) { it.exit.await() }

    @Suppress("TooGenericExceptionCaught") // Every failure after launch must release the owned child.
    private suspend fun <T : Any> execute(
        command: List<String>,
        deadline: RequestDeadline,
        register: (CoroutineScope, Process) -> OwnedRecordingProcess,
        consume: suspend (OwnedRecordingProcess) -> T,
    ): T {
        var owned: OwnedRecordingProcess? = null
        try {
            return withContext(Dispatchers.IO) {
                withTimeoutOrNull(deadline.remainingMillis()) {
                    coroutineContext.ensureActive()
                    val handle = register(this, launch(command))
                    owned = handle
                    coroutineContext.ensureActive()
                    deadline.remainingMillis()
                    consume(handle)
                } ?: throw TimeoutException("Recording command deadline exceeded")
            }
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    owned?.forceStop(RequestDeadline(CLEANUP_TIMEOUT_MS))
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw when (failure) {
                is CancellationException, is TimeoutException, is RecordingException -> failure
                else -> RecordingException("Unable to run recording command ${command.firstOrNull()}", failure)
            }
        }
    }

    private companion object {
        const val CLEANUP_TIMEOUT_MS = 1000L
    }
}
