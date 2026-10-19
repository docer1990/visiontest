package com.example.visiontest.cli

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
        ),
    )

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
