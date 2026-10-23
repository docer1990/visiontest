package dev.visiontest.cli

import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FlowBehaviorTest {
    @TestFactory
    fun scenarios() = FlowFeatureScenarios.tests(
        mapOf(
            "Reject an invalid later step before acting" to ::invalidLaterStep,
            "Execute ordered steps through one session" to ::orderedExecution,
            "Stop after an operation failure" to ::operationFailure,
            "Preserve uncertainty after a lost response" to { FlowLostResponseScenario().bindings() },
            "Collect bounded failure evidence" to { FlowEvidenceScenario().bindings() },
            "Return the final screen state" to { FlowFinalStateScenario().bindings() },
            "Report final observation failure separately" to { FlowFinalFailureScenario().bindings() },
        ),
        requireComplete = true,
    )

    private fun operationFailure(): Map<String, () -> Unit> {
        var steps = emptyList<FlowPreparedStep>()
        var report: FlowReport? = null
        val calls = mutableListOf<Int>()
        return mapOf(
            "Given a sequence whose second action returns a failure" to {
                steps = (1..3).map { index ->
                    FlowPreparedStep("step-$index", "press_home", FlowStepKind.ACTION) {
                        calls += index
                        """{"result":{"success":${index != 2},"error":"blocked"}}"""
                    }
                }
            },
            "When the caller submits the flow" to {
                report = kotlinx.coroutines.runBlocking { FlowExecutor().execute(Platform.Ios, steps) }
            },
            "Then the flow reports the failed step and skips the remaining steps" to {
                val actual = assertNotNull(report)
                assertFalse(actual.success)
                assertEquals(1, actual.exitCode)
                assertEquals("step-2", actual.failedStep)
                assertEquals(listOf("step-3"), actual.skippedSteps)
                assertEquals(listOf(1, 2), calls)
            },
        )
    }

    private fun orderedExecution(): Map<String, () -> Unit> {
        val calls = mutableListOf<String>()
        var steps = emptyList<FlowPreparedStep>()
        var report: FlowReport? = null
        return mapOf(
            "Given a valid sequence of actions and an element wait" to {
                steps = listOf(
                    FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                        calls += "action"
                        """{"result":{"success":true}}"""
                    },
                    FlowPreparedStep("step-2", "wait_for_element", FlowStepKind.WAIT, "appeared") {
                        calls += "wait"
                        """{"result":{"found":true}}"""
                    },
                )
            },
            "When the caller submits the flow" to {
                report = kotlinx.coroutines.runBlocking { FlowExecutor().execute(Platform.Android, steps) }
            },
            "Then each step succeeds in order using one device session" to {
                assertEquals(listOf("action", "wait"), calls)
                assertEquals(true, assertNotNull(report).success)
                assertEquals(listOf("succeeded", "succeeded"), report!!.steps.map { it.status })
            },
        )
    }

    private fun invalidLaterStep(): Map<String, () -> Unit> {
        val components = lazy<ComponentHolder> { error("Preflight initialized device components") }
        val output = ByteArrayOutputStream()
        var lines = emptyList<String>()
        var result: CliResult? = null
        return mapOf(
            "Given a sequence with a valid tap followed by an invalid command" to {
                lines = listOf("tap_by_coordinates 1 2", "invalid_command")
            },
            "When the caller submits the flow" to {
                val previous = System.out
                try {
                    System.setOut(PrintStream(output))
                    result = executeCliCommand {
                        FlowPreparation.prepare(Platform.Android, lines, components)
                        ""
                    }
                } finally {
                    System.setOut(previous)
                }
            },
            "Then no device components are created and stdout is empty" to {
                assertFalse(components.isInitialized())
                assertEquals("", output.toString())
                val actual = assertNotNull(result)
                assertEquals(2, actual.exitCode)
                assertNull(actual.stdout)
            },
        )
    }
}
