package dev.visiontest.common

import dev.visiontest.cli.CliExecutionContext
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files

/** Owns a caller's log destination without changing JVM streams. */
internal class RequestLogOutput(
    private val context: CliExecutionContext,
    private val stdout: (String) -> Unit,
) : AutoCloseable {
    private var writer: PrintWriter? = null
    private val destination by lazy { openDestination() }

    @Synchronized
    fun write(message: String) = destination(message)

    private fun openDestination(): (String) -> Unit {
        val path = context.loggingProperties["org.slf4j.simpleLogger.logFile"] ?: "System.err"
        return when {
            path.equals("System.out", ignoreCase = true) -> stdout
            path.equals("System.err", ignoreCase = true) -> context.diagnostic
            else -> try {
                val output = PrintWriter(Files.newBufferedWriter(context.resolve(path)))
                writer = output
                { message: String -> output.println(message); output.flush() }
            } catch (error: IOException) {
                context.diagnostic("SLF4J: Failed to open [$path]\n${error.stackTraceToString().trimEnd()}")
                context.diagnostic
            }
        }
    }

    @Synchronized
    override fun close() {
        writer?.close()
    }
}
