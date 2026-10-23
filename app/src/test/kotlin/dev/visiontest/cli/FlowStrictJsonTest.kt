package dev.visiontest.cli

import dev.visiontest.CommandExecutionException
import dev.visiontest.tools.ScreenshotSaver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowStrictJsonTest {
    @TempDir lateinit var directory: Path
    private val invalidValues = listOf("foo", "01", "+1", ".1", "NaN", "Infinity", "-Infinity",
        "[1,{\"nested\":invalid}]", "\"raw\tcontrol\"", "\"raw\u0000control\"")

    @Test
    fun `inspection rejects invalid JSON literals in additional fields`() {
        for (platform in Platform.entries) for (value in invalidValues) {
            val response = """{"result":{"success":true,"count":0,"elements":[],"extra":$value}}"""
            assertFailsWith<CommandExecutionException>(value) {
                inspectionOutput(response, true, InspectionCommand.INTERACTIVE_ELEMENTS, platform)
            }
        }
    }

    @Test
    fun `flow rejects malformed extra fields and stops without emitting invalid JSON`() = runBlocking {
        for (platform in Platform.entries) for (value in invalidValues) {
            var calls = 0
            val steps = (1..2).map { index ->
                FlowPreparedStep("step-$index", "press_home", FlowStepKind.ACTION) {
                    calls++
                    """{"result":{"success":true,"extra":$value}}"""
                }
            }
            val report = FlowExecutor().execute(platform, steps)
            assertFalse(report.success, value)
            assertEquals(1, report.exitCode)
            assertEquals(1, calls)
            assertEquals("skipped", report.steps[1].status)
            assertNull(report.steps[0].result)
        }
    }

    @Test
    fun `structured screenshots reject malformed extras before writing files`() = runBlocking {
        val saver = ScreenshotSaver("Test", "test", "server")
        for (value in invalidValues) {
            val path = directory.resolve("rejected.png")
            val result = saver.captureResult(path.toString()) {
                """{"result":{"success":true,"pngBase64":"AQID","extra":$value}}"""
            }
            assertFalse(result.success, value)
            assertNull(result.path)
            assertFalse(Files.exists(path))
        }
    }

    @Test
    fun `flow screenshot result rejects malformed additional fields`() = runBlocking {
        for (value in invalidValues) {
            val step = FlowPreparedStep("step-1", "screenshot", FlowStepKind.SCREENSHOT) {
                """{"success":true,"path":"/tmp/valid.png","extra":$value}"""
            }
            assertFalse(FlowExecutor().execute(Platform.Ios, listOf(step)).success, value)
        }
    }

    @Test
    fun `valid additive values and escaped controls remain accepted`() = runBlocking {
        val response = """{"result":{"success":true,"extra":[null,true,false,-0,0.1,1e300,1E-3,"escaped\tcontrol"]}}"""
        val step = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) { response }
        val report = FlowExecutor().execute(Platform.Android, listOf(step))
        assertTrue(report.success)
        assertEquals("true", report.steps.single().result?.get("success")?.jsonPrimitive?.content)
    }
}
