package dev.visiontest.daemon

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Child output is discarded so startup diagnostics cannot contaminate command output or grow logs. */
internal class DaemonLauncher(
    private val registry: DaemonRegistry,
    private val launchCommand: List<String> = command(),
    private val readinessTimeoutMillis: Long = 15_000,
    private val pollMillis: Long = 25,
) {
    fun ensureRunning(): DaemonRegistration {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readinessTimeoutMillis)
        var attempted = false
        while (System.nanoTime() < deadline) {
            val registration = registry.readMetadata()
            if (registration != null && ownerPresent()) return registration
            if (!attempted) {
                attempted = launchIfUnowned()
            }
            Thread.sleep(pollMillis)
        }
        throw IOException(
            "Daemon did not become ready within ${readinessTimeoutMillis}ms; child startup output is discarded",
        )
    }

    private fun launchIfUnowned(): Boolean {
        val lease = registry.acquire() ?: return false
        lease.use { it.removeStaleRegistration() }
        launch()
        return true
    }

    private fun ownerPresent(): Boolean {
        val lease = registry.acquire() ?: return true
        lease.close()
        return false
    }

    private fun launch() {
        val builder = ProcessBuilder(launchCommand)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment()["VISIONTEST_DAEMON_DIR"] = registry.directory.toAbsolutePath().toString()
        builder.start().outputStream.close()
    }

    companion object {
        fun command(
            javaHome: Path = Path.of(System.getProperty("java.home")),
            classpath: String = System.getProperty("java.class.path"),
        ): List<String> = listOf(
            javaHome.resolve("bin/java").toString(), "-cp", classpath,
            "dev.visiontest.MainKt", "--internal-daemon-host",
        )
    }
}
