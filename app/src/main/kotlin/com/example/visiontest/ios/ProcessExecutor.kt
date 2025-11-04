package com.example.visiontest.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ProcessExecutor(
    private val timeoutMillis: Long =5000L,
    private val logger: Logger = LoggerFactory.getLogger(ProcessExecutor::class.java)
) {
    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val errorOutput: String
    )

    suspend fun execute(vararg command: String): CommandResult {
        return withContext(Dispatchers.IO) {
            withTimeout(timeoutMillis) {
                logger.debug("Executing command: ${command.joinToString(" ")}")

                val process = ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()

                val output = StringBuilder()
                val errorOutput = StringBuilder()

                val outputReader = BufferedReader(InputStreamReader(process.inputStream))
                outputReader.useLines { lines ->
                    lines.forEach { output.append(it).append("\n") }
                }


                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                errorReader.useLines { lines ->
                    lines.forEach { errorOutput.append(it).append("\n") }
                }

                val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    throw CommandTimeoutException("Command timed out: ${command.joinToString(" ")}")
                }

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
}

class CommandTimeoutException(message: String) : Exception(message)