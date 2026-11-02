package dev.visiontest.daemon

import dev.visiontest.cli.CliResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real IPC/graph, synthetic output: no native endpoint or hardware is needed. */
object RetentionDaemonHost {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args.single())
        val resources = DaemonComponents()
        val hookField = Class.forName("java.lang.ApplicationShutdownHooks").getDeclaredField("hooks")
            .apply { isAccessible = true }
        DaemonHost(DaemonRegistry(directory), resources = resources, execution = { _, context, _ ->
            resources.acquire(context)
            val hooks = hookField.get(null) as Map<*, *>
            Files.writeString(directory.resolve("hook-count"), synchronized(hooks) { hooks.size.toString() })
            CliResult(0, "x".repeat(1024 * 1024), null)
        }).use { host -> host.start(); host.awaitStopped() }
    }
}

@Tag("e2e")
class DaemonRetentionE2ETest {
    @TempDir lateinit var directory: Path

    @Test fun `repeated requests bound holders shutdown hooks and retained request payloads`() {
        val runtime = directory.resolve("daemon")
        val javaHome = Path.of(System.getProperty("java.home"), "bin")
        val jar = System.getProperty("visiontest.jar")
        val classes = Path.of(RetentionDaemonHost::class.java.protectionDomain.codeSource.location.toURI())
        val host = ProcessBuilder(javaHome.resolve("java").toString(), "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-cp", "$classes:$jar", RetentionDaemonHost::class.java.name, runtime.toString())
            .redirectError(directory.resolve("host.err").toFile()).start()
        fun client(vararg args: String): String {
            val output = directory.resolve("client.out")
            val error = directory.resolve("client.err")
            val builder = ProcessBuilder(javaHome.resolve("java").toString(), "-jar", jar, *args)
                .redirectOutput(output.toFile()).redirectError(error.toFile())
            builder.environment()["VISIONTEST_DAEMON_DIR"] = runtime.toString()
            val child = builder.start()
            try {
                assertTrue(child.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, child.exitValue(), Files.readString(error))
                return Files.readString(output)
            } finally { if (child.isAlive) child.destroyForcibly() }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(runtime.resolve("ready.json")) && host.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(25)
            }
            assertTrue(Files.exists(runtime.resolve("ready.json")), Files.readString(directory.resolve("host.err")))
            assertEquals(1024 * 1024 + 1, client("get_device_info").length)
            val hooks = Files.readString(runtime.resolve("hook-count"))
            repeat(16) { assertEquals(1024 * 1024 + 1, client("get_device_info").length) }
            assertEquals(hooks, Files.readString(runtime.resolve("hook-count")))
            val histogram = directory.resolve("histogram.txt")
            val inspector = ProcessBuilder(javaHome.resolve("jcmd").toString(),
                host.pid().toString(), "GC.class_histogram")
                .redirectErrorStream(true).redirectOutput(histogram.toFile()).start()
            assertTrue(inspector.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, inspector.exitValue(), Files.readString(histogram))
            val lines = Files.readAllLines(histogram)
            fun count(name: String): Long = lines.firstOrNull { it.trim().endsWith(name) }
                ?.trim()?.split(Regex("\\s+"))?.get(1)?.toLong() ?: 0
            assertEquals(1L, count("dev.visiontest.cli.ComponentHolder"))
            assertTrue(count("dev.visiontest.daemon.DaemonHost\$Request") <= 1, Files.readString(histogram))
            assertTrue(count("dev.visiontest.daemon.DaemonMessage\$Execute") <= 1, Files.readString(histogram))
        } finally {
            try { client("daemon", "stop") } finally {
                if (!host.waitFor(5, TimeUnit.SECONDS)) host.destroyForcibly()
            }
        }
    }
}
