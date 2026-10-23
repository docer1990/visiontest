package com.example.visiontest.cli

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("e2e")
class LoggingE2ETest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `packaged logger suppresses routine noise and preserves concise stderr diagnostics`() {
        val source = tmp.resolve("LoggingProbe.java")
        source.writeText("""
            class LoggingProbe {
                public static void main(String[] args) {
                    var logger = org.slf4j.LoggerFactory.getLogger("com.example.visiontest.Probe");
                    logger.info("routine startup");
                    logger.warn("device unavailable");
                    logger.error("operation failed");
                    System.out.println("{\"success\":false}");
                }
            }
        """.trimIndent())
        val stdout = tmp.resolve("stdout")
        val stderr = tmp.resolve("stderr")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--class-path", requireNotNull(System.getProperty("visiontest.jar")), source.toString(),
        ).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "logger probe timed out")
            assertEquals(0, process.exitValue(), stderr.readText())
            assertEquals("{\"success\":false}\n", stdout.readText())
            assertEquals("WARN Probe - device unavailable\nERROR Probe - operation failed\n", stderr.readText())
        } finally {
            process.destroyForcibly()
        }
    }
}
