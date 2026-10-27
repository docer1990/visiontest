package dev.visiontest.daemon

import dev.visiontest.cli.CliTerminalDimensions
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Console detection matches the terminal framework without loading it in the client. */
internal fun callerTerminalDimensions(
    environment: Map<String, String>,
    interactive: Boolean = System.console() != null,
    probe: () -> String? = ::probeTerminalSize,
): CliTerminalDimensions {
    val measured = if (interactive) probe()?.trim()?.split(Regex("\\s+")) else null
    val height = dimension(measured?.getOrNull(0)) ?: dimension(environment["LINES"]) ?: DEFAULT_HEIGHT
    val width = dimension(measured?.getOrNull(1)) ?: dimension(environment["COLUMNS"]) ?: DEFAULT_WIDTH
    return CliTerminalDimensions(width, height)
}

// Mordant 2.x uses 79 columns when no terminal size or valid COLUMNS is available.
private const val DEFAULT_WIDTH = 79
private const val DEFAULT_HEIGHT = 24
private const val PROBE_TIMEOUT_MS = 250L
private const val MAX_PROBE_BYTES = 128

private fun dimension(value: String?): Int? = value?.toIntOrNull()
    ?.takeIf { it in 1..CliTerminalDimensions.MAX_DIMENSION }

private fun probeTerminalSize(): String? {
    val process = try {
        ProcessBuilder("/bin/stty", "size").redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (_: IOException) { return null }
    return try {
        if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null else {
            val bytes = process.inputStream.readNBytes(MAX_PROBE_BYTES + 1)
            bytes.takeIf { it.size <= MAX_PROBE_BYTES }?.toString(Charsets.US_ASCII)
        }
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        process.inputStream.close()
        process.outputStream.close()
        process.errorStream.close()
    }
}
