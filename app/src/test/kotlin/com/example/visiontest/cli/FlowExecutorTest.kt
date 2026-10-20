package com.example.visiontest.cli

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
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
    @Test
    fun `returned failures and malformed responses stop ordered execution`() = runBlocking {
        val responses = listOf(
            """{"result":{"success":false,"error":"blocked"}}""",
            """{"error":{"code":-32601,"message":"Unknown method","data":[]}}""",
            """{"result":{"success":"true"}}""",
            """{"result":{"success":true,"error":false}}""",
            """{"result":{},"error":{"code":1,"message":"ambiguous"}}""",
            """{"error":{"code":"1","message":"bad"}}""",
            "null", "broken", """{"result":{}}""",
        )
        for (platform in Platform.entries) for (response in responses) for (index in 0..1) {
            var calls = 0
            val steps = (0..2).map { position ->
                FlowPreparedStep("step-${position + 1}", "press_home", FlowStepKind.ACTION) {
                    calls++
                    if (position == index) response else """{"result":{"success":true}}"""
                }
            }
            val report = FlowExecutor().execute(platform, steps)
            assertFalse(report.success, response)
            assertEquals(1, report.exitCode)
            assertEquals("step-${index + 1}", report.failedStep)
            assertEquals(index + 1, calls)
            assertEquals("failed", report.steps[index].status)
            assertEquals("confirmed", report.steps[index].certainty)
            assertFalse(report.text().contains("action effect is unknown"))
            assertFalse(report.text().contains("Inspect the UI before retrying"))
            assertEquals((index + 1..2).map { "step-${it + 1}" }, report.skippedSteps)
        }
    }

    @Test
    fun `thrown failures retain CLI mapping and still emit complete report`() = runBlocking {
        val exceptions = listOf(
            RuntimeException("generic") to 1,
            IllegalArgumentException("invalid") to 2,
            com.example.visiontest.ServerNotRunningException("unreachable") to 3,
            com.example.visiontest.NoDeviceAvailableException("missing") to 4,
            CliExit(ExitCode.PlatformNotSupported, "unsupported") to 5,
        )
        for ((failure, code) in exceptions) for (platform in Platform.entries) {
            val step = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) { throw failure }
            val result = executeCliResultCommand {
                val report = FlowExecutor().execute(platform, listOf(step))
                CliResult(report.exitCode, report.toJson().toString(), report.error?.message)
            }
            assertEquals(code, result.exitCode)
            assertTrue(result.stdout!!.contains("\"failedStep\":\"step-1\""))
            assertEquals(failure.message, result.stderr)
        }
    }

    @Test
    fun `invalid absence and screenshot paths never count as success`() = runBlocking {
        val cases = listOf(
            FlowPreparedStep("step-1", "wait_for_element", FlowStepKind.WAIT, "gone") { """{"result":{}}""" },
            FlowPreparedStep("step-1", "wait_for_element", FlowStepKind.WAIT, "gone") {
                """{"result":{"found":true}}"""
            },
            FlowPreparedStep("step-1", "screenshot", FlowStepKind.SCREENSHOT) {
                """{"success":true,"path":"relative.png"}"""
            },
            FlowPreparedStep("step-1", "screenshot", FlowStepKind.SCREENSHOT) { """{"success":false}""" },
            FlowPreparedStep("step-1", "screenshot", FlowStepKind.SCREENSHOT) {
                """{"success":true,"path":"/tmp/\u0000.png"}"""
            },
        )
        for (step in cases) {
            val report = FlowExecutor().execute(Platform.Ios, listOf(step))
            assertFalse(report.success)
            assertEquals(1, report.exitCode)
        }
    }

    @Test
    fun `step and overall budgets interrupt processing and never replay`() = runBlocking {
        for (overall in listOf(false, true)) {
            var calls = 0
            val step = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                calls++
                kotlinx.coroutines.delay(500)
                """{"result":{"success":true}}"""
            }
            val report = FlowExecutor().execute(Platform.Android, listOf(step, step.copy(id = "step-2")),
                stepTimeoutMs = if (overall) 1000 else 20, timeoutMs = if (overall) 20 else 1000)
            assertEquals(1, report.exitCode)
            assertEquals(1, calls)
            assertEquals("failed", report.steps.first().status)
            assertEquals(listOf("step-2"), report.skippedSteps)
            assertEquals("TimeoutException", report.error?.type)
        }
    }

    @Test
    fun `step cancellation with an active caller still produces a report`() = runBlocking {
        val step = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
            throw kotlinx.coroutines.CancellationException("operation cancelled")
        }
        val report = FlowExecutor().execute(Platform.Android, listOf(step))
        assertFalse(report.success)
        assertEquals("CancellationException", report.error?.type)
    }

    @Test
    fun `parent cancellation propagates without running later steps`() = runBlocking {
        var laterCalls = 0
        val step = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
            kotlinx.coroutines.delay(5000)
            laterCalls++
            """{"result":{"success":true}}"""
        }
        kotlin.test.assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            kotlinx.coroutines.withTimeout(20) { FlowExecutor().execute(Platform.Android, listOf(step)) }
        }
        assertEquals(0, laterCalls)
    }

}
