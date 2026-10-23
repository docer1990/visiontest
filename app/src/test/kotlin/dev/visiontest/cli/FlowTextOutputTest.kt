package dev.visiontest.cli

import dev.visiontest.ServerNotRunningException
import dev.visiontest.cli.commands.FlowCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowTextOutputTest {
    private val elements = """{"success":true,"count":1,"elements":[{"text":" Login ","resourceId":"login",
        "className":"Button","contentDescription":"Sign in","bounds":"[1,2][3,4]","isEnabled":true,
        "extra":{"nested":[1,"retained"]}}],"catalog":"complete"}""".replace("\n", "")

    @Test
    fun `text returns complete interactive final observations and skipped evidence on both platforms`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method -> MockResponse().setBody(if (method == "ui.getInteractiveElements")
                """{"result":$elements}""" else """{"result":{"success":true}}""") }
            val result = run(fixture.components, platform,
                "--observe", "interactive-elements", "--on-failure", "hierarchy")
            assertEquals(0, result.exitCode)
            val text = assertNotNull(result.stdout)
            assertTrue(text.startsWith("Flow succeeded: 1 completed, 0 skipped"))
            assertRecord(text, "Final", "interactive-elements", "succeeded", elements)
            assertRecord(text, "Evidence", "hierarchy", "skipped")
            assertFalse(text.contains("jsonrpc"))
        }
    }

    @Test
    fun `text returns complete interactive failure evidence and skipped final observations on both platforms`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method -> MockResponse().setBody(if (method == "ui.getInteractiveElements")
                """{"result":$elements}""" else """{"result":{"success":false,"error":"original blocked"}}""") }
            val result = run(fixture.components, platform,
                "--observe", "hierarchy", "--on-failure", "interactive-elements")
            assertEquals(1, result.exitCode)
            assertEquals("original blocked", result.stderr)
            val text = assertNotNull(result.stdout)
            assertTrue(text.contains("original blocked"))
            assertRecord(text, "Final", "hierarchy", "skipped")
            assertRecord(text, "Evidence", "interactive-elements", "succeeded", elements)
        }
    }

    @Test
    fun `text reports evidence errors without replacing the original mapped failure`() {
        for (platform in Platform.entries) {
            val components = lazy<ComponentHolder> { throw ServerNotRunningException("original unavailable") }
            val result = run(components, platform, "--observe", "screenshot", "--on-failure", "interactive-elements")
            assertEquals(3, result.exitCode)
            assertEquals("original unavailable", result.stderr)
            val text = assertNotNull(result.stdout)
            assertTrue(text.contains("failed step step-1. original unavailable"))
            assertRecord(text, "Final", "screenshot", "skipped")
            val record = assertRecord(text, "Evidence", "interactive-elements", "failed")
            assertEquals("ServerNotRunningException", record.getValue("error").jsonObject
                .getValue("type").jsonPrimitive.content)
            assertEquals("original unavailable", record.getValue("error").jsonObject
                .getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun `text includes artifact paths once per observation and retains step paths`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method -> MockResponse().setBody(when (method) {
                "ui.screenshot" -> """{"result":{"success":true,"pngBase64":"AQID"}}"""
                "ui.dumpHierarchy" -> """{"result":{"success":true,"hierarchy":"<hierarchy/>"}}"""
                else -> """{"result":{"success":true}}"""
            }) }
            val stepPath = Files.createTempFile("flow-text-step", ".png")
            val result = run(fixture.components, platform, "--step", "screenshot --output '$stepPath'",
                "--observe", "screenshot", "--observe", "hierarchy")
            assertEquals(0, result.exitCode)
            val text = assertNotNull(result.stdout)
            assertTrue(text.lines().contains(stepPath.toString()))
            val paths = listOf("screenshot", "hierarchy").map { kind ->
                val record = assertRecord(text, "Final", kind, "succeeded")
                Path.of(record.getValue("result").jsonObject.getValue("path").jsonPrimitive.content)
            }
            try {
                assertArtifacts(text, paths)
                assertFalse(text.contains("pngBase64"))
                assertFalse(text.contains("<hierarchy"))
            } finally {
                paths.forEach(Files::delete)
                Files.delete(paths.first().parent)
                Files.delete(stepPath)
            }
        }
    }

    private fun assertArtifacts(text: String, paths: List<Path>) {
        paths.forEach { path ->
            assertTrue(path.isAbsolute && Files.exists(path))
            assertEquals(1, text.split(path.toString()).size - 1)
        }
    }

    private fun run(components: Lazy<ComponentHolder>, platform: Platform, vararg options: String): CliResult {
        var result: CliResult? = null
        FlowCommand(components, runner = { result = executeCliResultCommand(it) })
            .parse(listOf("-p", platform.value, "--step", "press_home") + options)
        return assertNotNull(result)
    }

    private fun assertRecord(
        text: String,
        category: String,
        kind: String,
        status: String,
        result: String? = null,
    ): JsonObject {
        val prefix = "$category $kind: "
        val line = assertNotNull(text.lines().find { it.startsWith(prefix) }, text)
        val record = Json.parseToJsonElement(line.removePrefix(prefix)).jsonObject
        assertEquals(kind, record.getValue("kind").jsonPrimitive.content)
        assertEquals(status, record.getValue("status").jsonPrimitive.content)
        result?.let { assertEquals(Json.parseToJsonElement(it), record["result"]) }
        return record
    }
}
