package com.example.visiontest.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("e2e")
class FlowCommandE2ETest {
    @TempDir lateinit var directory: Path
    private data class Output(val code: Int, val stdout: String, val stderr: String)

    private fun run(args: List<String>, stdin: String = ""): Output {
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("visiontest.jar"), "flow", *args.toTypedArray())
            .directory(directory.toFile()).start()
        process.outputStream.bufferedWriter().use { it.write(stdin) }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return Output(process.exitValue(), stdout, stderr)
    }

    @Test
    fun `flow help is available from the packaged CLI`() {
        val result = run(listOf("--help"))
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("--step-timeout"))
        assertTrue(result.stdout.contains("--stdin"))
    }

    @Test
    fun `real CLI preflight errors preserve empty stdout for every input mode`() {
        val source = "press_home\ninvalid_command\n"
        val file = directory.resolve("invalid.flow")
        Files.writeString(file, source)
        val modes = listOf(listOf("--step", "press_home", "--step", "invalid_command"),
            listOf("--file", file.toString()), listOf("--stdin"))
        for (platform in Platform.entries) for (mode in modes) {
            val result = run(listOf("-p", platform.value, "--json") + mode, source)
            assertEquals(2, result.code)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("not supported in flows"))
        }
    }

    @Test
    fun `real CLI emits one complete JSON report after execution starts and fails`() {
        for (platform in Platform.entries) {
            val result = run(listOf("-p", platform.value, "--json", "--step", "screenshot --output .",
                "--step", "press_home", "--step-timeout", "100", "--timeout", "500"))
            assertTrue(result.code != 0)
            assertTrue(result.stdout.endsWith('\n'))
            assertEquals(1, result.stdout.lines().count { it.isNotEmpty() })
            val report = Json.parseToJsonElement(result.stdout).jsonObject
            assertEquals(result.code.toString(), report.getValue("exitCode").jsonPrimitive.content)
            assertEquals("false", report.getValue("success").jsonPrimitive.content)
            assertEquals("step-1", report.getValue("failedStep").jsonPrimitive.content)
            assertEquals("skipped", report.getValue("steps").jsonArray[1].jsonObject
                .getValue("status").jsonPrimitive.content)
            assertFalse(report.containsKey("jsonrpc"))
        }
    }
}
