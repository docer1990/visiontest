package dev.visiontest.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("e2e")
class PerformanceTraceE2ETest {
    @TempDir lateinit var directory: Path
    private data class Output(val code: Int, val stdout: String, val stderr: String)
    private fun run(vararg args: String, environment: String? = null): Output {
        val builder = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("visiontest.jar"), "--standalone", *args).directory(directory.toFile())
        builder.environment().remove("VISIONTEST_TRACE_PERFORMANCE")
        if (environment != null) builder.environment()["VISIONTEST_TRACE_PERFORMANCE"] = environment
        val process = builder.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return Output(process.exitValue(), stdout, stderr)
    }
    private fun rows(path: Path) = Files.readAllLines(path).map { JsonParser.parseString(it).asJsonObject }

    @Test fun `version and help preserve output and close trace before exit`() {
        for (argument in listOf("--version", "--help")) {
            val plain = run(argument)
            val path = directory.resolve(argument.removePrefix("--") + ".jsonl")
            val traced = run("--trace-performance", path.toString(), argument)
            assertEquals(plain, traced)
            assertTrue(rows(path).last().get("complete").asBoolean)
            assertEquals(1, rows(path).count { it.get("stage")?.asString == "invocation" })
        }
    }
    @Test fun `invalid wait records preparation without device initialization`() {
        val args = arrayOf("wait_for_element", "--platform", "android", "--timeout", "0")
        val plain = run(*args)
        val path = directory.resolve("invalid.jsonl")
        val traced = run("--trace-performance", path.toString(), *args)
        assertEquals(plain, traced)
        assertEquals(2, traced.code)
        assertTrue(rows(path).any { it.get("stage")?.asString == "cli.prepare" })
        assertFalse(rows(path).any { it.get("stage")?.asString == "component.init" })
        val invocation = rows(path).single { it.get("stage")?.asString == "invocation" }
        assertEquals("wait_for_element", invocation.get("operation").asString)
        assertEquals("android", invocation.get("platform").asString)
        assertTrue(rows(path).last().get("complete").asBoolean)
    }
    @Test fun `invalid flow retains its operation name in preparation and invocation`() {
        val path = directory.resolve("flow.jsonl")
        val result = run("--trace-performance", path.toString(), "flow", "--platform", "android",
            "--step", "invalid_command")
        assertEquals(2, result.code)
        assertEquals("", result.stdout)
        val events = rows(path)
        for (stage in listOf("cli.prepare", "invocation")) {
            val event = events.single { it.get("stage")?.asString == stage }
            assertEquals("flow", event.get("operation").asString)
            assertEquals("android", event.get("platform").asString)
        }
        assertFalse(events.any { it.get("stage")?.asString == "component.init" })
        assertTrue(events.last().get("complete").asBoolean)
    }
    @Test fun `CLI ignores environment and rejects missing or blank option values`() {
        val ignored = directory.resolve("ignored.jsonl")
        assertEquals(run("--version"), run("--version", environment = ignored.toString()))
        assertFalse(Files.exists(ignored))
        assertEquals(2, run("--trace-performance").code)
        assertEquals(2, run("--trace-performance", " ", "--version").code)
    }
    @Test fun `unavailable trace emits fixed diagnostic and preserves normal result`() {
        val args = arrayOf("init", "--agent", "codex")
        val plain = run(*args)
        val result = run("--trace-performance", directory.toString(), *args)
        assertEquals(0, plain.code)
        assertEquals(plain.code, result.code)
        assertEquals(plain.stdout, result.stdout)
        assertEquals("VisionTest performance trace is unavailable or incomplete.\n" + plain.stderr, result.stderr)
    }
    @Test fun `literal trace flag in typed input is not treated as root option`() {
        val args = arrayOf("input_text", "--platform", "android",
            "--target-resource-id", "fixture-input", "--timeout", "0", "--", "--trace-performance")
        val plain = run(*args)
        val path = directory.resolve("input.jsonl")
        val traced = run("--trace-performance", path.toString(), *args)
        assertEquals(Output(2, "", "timeoutMs must be between 1 and 30000, got 0\n"), plain)
        assertEquals(plain, traced)
        val events = rows(path)
        val invocation = events.single { it.get("stage")?.asString == "invocation" }
        assertEquals("input_text", invocation.get("operation").asString)
        assertEquals("android", invocation.get("platform").asString)
        assertTrue(events.any { it.get("stage")?.asString == "cli.prepare" })
        assertFalse(events.any { it.get("stage")?.asString in setOf("component.init", "health", "http.exchange") })
        assertTrue(events.last().get("complete").asBoolean)
        assertFalse(Files.readString(path).contains("--trace-performance"))
    }
}
