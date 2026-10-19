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
        mapOf("Reject an invalid later step before acting" to ::invalidLaterStep),
    )

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
