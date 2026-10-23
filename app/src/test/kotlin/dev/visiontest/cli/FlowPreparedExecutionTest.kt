package dev.visiontest.cli

import dev.visiontest.android.AutomationClient
import dev.visiontest.cli.commands.ScreenshotCommand
import dev.visiontest.cli.commands.TapOnElementCommand
import dev.visiontest.cli.commands.WaitForElementCommand
import dev.visiontest.common.DeviceConfig
import dev.visiontest.discovery.ToolDiscovery
import dev.visiontest.ios.IOSAutomationClient
import dev.visiontest.tools.AndroidAutomationToolRegistrar
import dev.visiontest.tools.AndroidDeviceToolRegistrar
import dev.visiontest.tools.IOSAutomationToolRegistrar
import dev.visiontest.tools.IOSDeviceToolRegistrar
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowPreparedExecutionTest {
    @TempDir
    lateinit var directory: Path
    private val server = MockWebServer()
    private lateinit var components: Lazy<ComponentHolder>

    @BeforeTest
    fun setup() {
        server.start()
        val androidClient = AutomationClient(server.hostName, server.port)
        val iosClient = IOSAutomationClient(server.hostName, server.port)
        val backend = mockk<DeviceConfig>()
        val logger = LoggerFactory.getLogger(javaClass)
        val discovery = ToolDiscovery(logger)
        components = lazy {
            ComponentHolder(
                mockk(), mockk(), androidClient, iosClient, AndroidDeviceToolRegistrar(backend),
                AndroidAutomationToolRegistrar(backend, androidClient, discovery), IOSDeviceToolRegistrar(backend),
                IOSAutomationToolRegistrar(backend, iosClient, discovery, logger),
            )
        }
    }

    @AfterTest
    fun cleanup() = server.shutdown()

    private fun response(result: String): String {
        val envelope = """{"jsonrpc":"2.0","id":1,"result":$result}"""
        server.enqueue(MockResponse().setBody("OK"))
        server.enqueue(MockResponse().setBody("OK"))
        server.enqueue(MockResponse().setBody(envelope))
        return envelope
    }

    private fun request(): JsonObject {
        repeat(2) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        return JsonParser.parseString(request.body.readUtf8()).asJsonObject
    }

    private fun execute(platform: Platform, line: String): String {
        val step = FlowPreparation.prepare(platform, listOf(line), components).single()
        val result = executeCliCommand(step.execute)
        assertEquals(0, result.exitCode, result.stderr)
        return assertNotNull(result.stdout)
    }

    @Test
    fun `dispatched malformed action is uncertain but valid backend failures are confirmed`() {
        val results = listOf(
            """{"success":"true"}""" to "uncertain",
            """{"success":true,"error":{}}""" to "uncertain",
            """{"success":false,"error":"blocked"}""" to "failed",
        )
        for (platform in Platform.entries) for ((result, status) in results) {
            response(result)
            val steps = FlowPreparation.prepare(platform, listOf("press_home", "press_home"), components)
            val report = kotlinx.coroutines.runBlocking { FlowExecutor().execute(platform, steps) }
            assertEquals(status, report.steps.first().status)
            assertEquals(if (status == "uncertain") "unknown" else "confirmed", report.steps.first().certainty)
            assertEquals(listOf("step-2"), report.skippedSteps)
            request()
        }
    }

    @Test
    fun `read and readiness failures have confirmed outcomes without action dispatch`() {
        for (platform in Platform.entries) {
            response("""{"found":"false"}""")
            val steps = FlowPreparation.prepare(platform,
                listOf("wait_for_element --text Spinner --gone", "press_home"), components)
            val report = kotlinx.coroutines.runBlocking { FlowExecutor().execute(platform, steps) }
            assertEquals("failed", report.steps.first().status)
            assertEquals("confirmed", report.steps.first().certainty)
            request()
            server.enqueue(MockResponse().setResponseCode(503))
            val action = FlowPreparation.prepare(platform, listOf("press_home"), components)
            val unavailable = kotlinx.coroutines.runBlocking { FlowExecutor().execute(platform, action) }
            assertEquals(3, unavailable.exitCode)
            assertEquals("failed", unavailable.steps.single().status)
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `operation wait budget bounds a stalled find response before step timeout`() {
        for (platform in Platform.entries) {
            response("""{"found":true}""")
            server.enqueue(MockResponse().setBody("OK"))
            server.enqueue(MockResponse().setBody("OK"))
            server.enqueue(MockResponse().setBody("""{"result":{"found":true}}""")
                .setBodyDelay(2, TimeUnit.SECONDS))
            // Consume the first sequence to warm the scoped HTTP client outside the measured wait.
            val warm = FlowPreparation.prepare(platform, listOf("wait_for_element --text Warm"), components)
            assertTrue(kotlinx.coroutines.runBlocking { FlowExecutor().execute(platform, warm) }.success)
            request()
            val steps = FlowPreparation.prepare(platform,
                listOf("wait_for_element --text Done --timeout 100"), components)
            val started = System.nanoTime()
            val report = kotlinx.coroutines.runBlocking {
                FlowExecutor().execute(platform, steps, stepTimeoutMs = 3000)
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertFalse(report.success)
            assertTrue(elapsed < 1000, "Operation wait took $elapsed ms")
            assertEquals("confirmed", report.steps.single().certainty)
            request()
        }
    }

    @Test
    fun `executor uses one prepared component holder for ordered actions and waits`() {
        for (platform in Platform.entries) {
            response("""{"success":true}""")
            response("""{"found":true}""")
            val steps = FlowPreparation.prepare(platform,
                listOf("press_home", "wait_for_element --text Done"), components)
            val report = kotlinx.coroutines.runBlocking { FlowExecutor().execute(platform, steps) }
            assertTrue(report.success)
            assertTrue(components.isInitialized())
            assertEquals("device.pressHome", request()["method"].asString)
            assertEquals("ui.findElement", request()["method"].asString)
        }
    }

    @Test
    fun `flow waits preserve raw appearance and disappearance responses`() {
        for (platform in Platform.entries) {
            for (gone in listOf(false, true)) {
                val envelope = response("""{"found":${!gone}}""")
                val args = if (gone) "--gone" else ""
                assertEquals(envelope, execute(platform, "wait_for_element --text Spinner $args"))
                assertEquals("ui.findElement", request()["method"].asString)
            }
        }
    }

    @Test
    fun `flow screenshot success exposes a saved absolute path without image data`() {
        for (platform in Platform.entries) {
            val output = directory.resolve("${platform.value} shot.png")
            response("""{"success":true,"pngBase64":"AQID"}""")
            val result = JsonParser.parseString(execute(platform, "screenshot --output '$output'")).asJsonObject
            assertEquals(setOf("success", "path"), result.keySet())
            assertTrue(result["success"].asBoolean)
            assertEquals(output.toAbsolutePath().toString(), result["path"].asString)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(output))
            assertEquals("ui.screenshot", request()["method"].asString)
        }
    }

    @Test
    fun `flow screenshot failure exposes typed failure instead of parsing prose`() {
        for (platform in Platform.entries) {
            response("""{"success":false,"error":"screen unavailable"}""")
            val result = JsonParser.parseString(execute(platform, "screenshot")).asJsonObject
            assertEquals(setOf("success", "error"), result.keySet())
            assertFalse(result["success"].asBoolean)
            assertTrue(result["error"].asString.contains("screen unavailable"))
            request()
        }
    }

    @Test
    fun `flow tap preserves returned failure while standalone keeps its thrown failure`() {
        for (platform in Platform.entries) {
            val envelope = response("""{"success":false,"error":"not tappable"}""")
            assertEquals(envelope, execute(platform, "tap_on_element --text Login"))
            assertEquals(10000, request()["params"].asJsonObject["timeoutMs"].asInt)
            response("""{"success":false,"error":"not tappable"}""")
            var result: CliResult? = null
            TapOnElementCommand(components) { result = executeCliCommand(it) }
                .parse(listOf("-p", platform.value, "--text", "Login"))
            assertEquals(1, result?.exitCode)
            request()
        }
    }

    @Test
    fun `standalone waits and screenshots return text results`() {
        for (platform in Platform.entries) {
            response("""{"found":false}""")
            var result: CliResult? = null
            WaitForElementCommand(components) { result = executeCliCommand(it) }
                .parse(listOf("-p", platform.value, "--text", "Spinner", "--gone"))
            assertEquals(0, result?.exitCode)
            assertEquals("Element gone.", result?.stdout)
            request()
            response("""{"success":false,"error":"screen unavailable"}""")
            ScreenshotCommand(components) { result = executeCliCommand(it) }.parse(listOf("-p", platform.value))
            assertEquals(0, result?.exitCode)
            assertTrue(assertNotNull(result?.stdout).startsWith("Screenshot failed"))
            request()
        }
    }

    @Test
    fun `deferred execution keeps independent parsed values and literal arguments`() {
        val steps = FlowPreparation.prepare(Platform.Android, listOf(
            "press_key 066", "press_key enter", "input_text -- --platform", "input_text @literal",
            "input_text ''", "tap_on_element --text --help --timeout 1",
        ), components)
        assertFalse(components.isInitialized())
        val params = steps.map { step ->
            response("""{"success":true}""")
            assertEquals(0, executeCliCommand(step.execute).exitCode)
            request()["params"].asJsonObject
        }
        assertEquals(66, params[0]["keyCode"].asInt)
        assertEquals("enter", params[1]["action"].asString)
        assertEquals("--platform", params[2]["text"].asString)
        assertEquals("@literal", params[3]["text"].asString)
        assertEquals("", params[4]["text"].asString)
        assertEquals("--help", params[5]["text"].asString)
        assertEquals(1, params[5]["timeoutMs"].asInt)
    }

    @Test
    fun `flow screenshots without explicit paths receive unique artifact names`() {
        val paths = mutableListOf<Path>()
        try {
            repeat(2) {
                response("""{"success":true,"pngBase64":"AQID"}""")
                val result = JsonParser.parseString(execute(Platform.Android, "screenshot")).asJsonObject
                paths.add(Path.of(result["path"].asString))
                request()
            }
            assertEquals(2, paths.toSet().size)
            assertTrue(paths.all { it.fileName.toString().startsWith("android_flow_") })
        } finally {
            paths.forEach(Files::deleteIfExists)
        }
    }
}
