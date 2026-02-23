package com.example.visiontest.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ProcessExecutor(
    private val timeoutMillis: Long = 5000L,
    private val logger: Logger = LoggerFactory.getLogger(ProcessExecutor::class.java)
) {
    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val errorOutput: String
    )

    suspend fun execute(vararg command: String): CommandResult {
        return withContext(Dispatchers.IO) {
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
            CommandResult(exitCode, outputStr, errorStr)
        }
    }
}

class CommandTimeoutException(message: String) : Exception(message)
