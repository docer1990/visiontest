package dev.visiontest.cli

import dev.visiontest.config.AutomationConfig
import dev.visiontest.config.IOSAutomationConfig
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.BindException
import java.util.Collections
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("e2e")
class FlowCommandE2ETest {
    @TempDir lateinit var directory: Path
    private data class Output(val code: Int, val stdout: String, val stderr: String)

    private fun run(args: List<String>, stdin: String = ""): Output {
        val builder = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("visiontest.jar"), "flow", *args.toTypedArray())
        return runProcess(builder, stdin)
    }

    private fun runProcess(builder: ProcessBuilder, stdin: String = "", timeoutMillis: Long = 20_000): Output {
        val captureDirectory = Files.createTempDirectory(directory, "process-")
        val input = captureDirectory.resolve("stdin")
        val stdout = captureDirectory.resolve("stdout")
        val stderr = captureDirectory.resolve("stderr")
        try {
            Files.writeString(input, stdin)
            val process = builder.directory(directory.toFile())
                .redirectInput(input.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
            try {
                assertTrue(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS), "flow subprocess timed out")
                return Output(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
        } finally {
            listOf(input, stdout, stderr, captureDirectory).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `subprocess deadline applies while output streams remain open`() {
        val start = System.nanoTime()
        val failure = assertFailsWith<AssertionError> {
            runProcess(ProcessBuilder("/bin/sh", "-c", "printf out; printf err >&2; exec sleep 2"),
                timeoutMillis = 100)
        }
        assertTrue(failure.message.orEmpty().contains("flow subprocess timed out"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_500)
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
    fun `Android CLI emits a complete report for an isolated operation failure`() =
        assertOperationFailureReport(Platform.Android, AutomationConfig.DEFAULT_PORT)

    @Test
    fun `iOS CLI emits a complete report for an isolated operation failure`() =
        assertOperationFailureReport(Platform.Ios, IOSAutomationConfig.DEFAULT_PORT)

    private fun assertOperationFailureReport(platform: Platform, port: Int) {
        MockWebServer().use { server ->
            val calls = Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return MockResponse().setBody("OK")
                    calls += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        .getValue("method").jsonPrimitive.content
                    return MockResponse().setBody(
                        """{"result":{"success":false,"error":"isolated screenshot failure"}}"""
                    )
                }
            }
            // Own the destination before starting the CLI; never contact an existing native server.
            try {
                server.start(port)
            } catch (_: BindException) {
                assumeTrue(false, "$platform automation port is occupied; no live server was contacted")
            }
            val result = run(listOf("-p", platform.value, "--json", "--step", "screenshot",
                "--step", "press_home"))
            assertEquals(1, result.code)
            assertTrue(result.stdout.endsWith('\n'))
            assertEquals(1, result.stdout.lines().count { it.isNotEmpty() })
            val report = Json.parseToJsonElement(result.stdout).jsonObject
            assertEquals(result.code.toString(), report.getValue("exitCode").jsonPrimitive.content)
            assertEquals("false", report.getValue("success").jsonPrimitive.content)
            assertEquals("step-1", report.getValue("failedStep").jsonPrimitive.content)
            val steps = report.getValue("steps").jsonArray
            assertEquals("failed", steps[0].jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals("skipped", steps[1].jsonObject.getValue("status").jsonPrimitive.content)
            assertTrue(report.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
                .contains("isolated screenshot failure"))
            assertEquals(listOf("ui.screenshot"), calls.toList())
            assertFalse(report.containsKey("jsonrpc"))
        }
    }
}
