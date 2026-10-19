package com.example.visiontest.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowExecutorTest {
    @Test
    fun `success report unwraps actions waits and saved screenshots on both platforms`() = runBlocking {
        for (platform in Platform.entries) {
            val executed = mutableListOf<String>()
            val data = listOf(
                Triple(FlowStepKind.ACTION, null, """{"result":{"success":true}}"""),
                Triple(FlowStepKind.WAIT, "appeared", """{"result":{"found":true}}"""),
                Triple(FlowStepKind.WAIT, "gone", """{"result":{"found":false}}"""),
                Triple(FlowStepKind.SCREENSHOT, null, """{"success":true,"path":"/tmp/flow.png"}"""),
            )
            val steps = data.mapIndexed { index, (kind, condition, response) ->
                FlowPreparedStep("step-${index + 1}", kind.name, kind, condition) {
                    executed += "step-${index + 1}"
                    response
                }
            }
            val report = FlowExecutor().execute(platform, steps)
            assertTrue(report.success)
            assertEquals(0, report.exitCode)
            assertEquals(steps.map { it.id }, executed)
            assertEquals(setOf("schemaVersion", "platform", "success", "exitCode", "steps", "failedStep",
                "skippedSteps", "observations", "evidence", "error"), report.toJson().keys)
            val text = report.toJson().toString()
            assertFalse(text.contains("jsonrpc"))
            assertFalse(text.contains("pngBase64"))
            assertTrue(report.text().contains("/tmp/flow.png"))
            assertEquals(true, report.steps[1].result?.get("satisfied")?.toString()?.toBoolean())
            assertEquals(true, report.steps[2].result?.get("satisfied")?.toString()?.toBoolean())
        }
    }
}
