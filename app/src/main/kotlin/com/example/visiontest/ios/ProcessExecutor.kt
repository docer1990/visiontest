package com.example.visiontest.ios

import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.performance.TraceStage
import com.example.visiontest.performance.OperationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ProcessExecutor internal constructor(
    private val timeoutMillis: Long = 5000L,
    private val logger: Logger = LoggerFactory.getLogger(ProcessExecutor::class.java),
    private val trace: TraceRecorder,
) {
    constructor(timeoutMillis: Long = 5000L, logger: Logger = LoggerFactory.getLogger(ProcessExecutor::class.java)) :
        this(timeoutMillis, logger, TraceRecorder.Disabled)
    constructor() : this(trace = TraceRecorder.Disabled)

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val errorOutput: String
    )

    suspend fun execute(vararg command: String): CommandResult {
        return trace.span(TraceStage.SIMCTL) {
            withContext(Dispatchers.IO) {
                logger.debug("Executing command: ${command.joinToString(" ")}")

                val process = ProcessBuilder(*command)
                    .start()

                val output = StringBuilder()
                val errorOutput = StringBuilder()

                // Drain streams in background threads to prevent pipe buffer deadlock
                // and allow waitFor to proceed independently.
                // Thread.join() below provides the happens-before guarantee for safe
                // reading of output/errorOutput after the threads complete.
                val outputThread = Thread {
                    BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                        lines.forEach { output.append(it).append("\n") }
                    }
                }.apply { isDaemon = true; start() }

                val errorThread = Thread {
                    BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                        lines.forEach { errorOutput.append(it).append("\n") }
                    }
                }.apply { isDaemon = true; start() }

                val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    outputThread.join(1000)
                    errorThread.join(1000)
                    throw CommandTimeoutException("Command timed out: ${command.joinToString(" ")}")
                }

                // Process exited — streams will close, threads will finish promptly
                outputThread.join(5000)
                errorThread.join(5000)

                val exitCode = process.exitValue()
                val outputStr = output.toString().trim()
                val errorStr = errorOutput.toString().trim()

                logger.debug("Command exit code: {}", exitCode)
                if (exitCode != 0) {
                    logger.warn("Command failed: {}", errorStr)
                }
                trace.operationOutcome(if (exitCode == 0) OperationOutcome.SUCCESS else OperationOutcome.FAILURE)
                CommandResult(exitCode, outputStr, errorStr)
            }
        }
    }
}

class CommandTimeoutException(message: String) : Exception(message)
