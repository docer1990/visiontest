package dev.visiontest.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FlowPreparationTest {
    private val components = lazy<ComponentHolder> { error("Preparation initialized components") }

    private fun result(platform: Platform, vararg lines: String) = executeCliCommand {
        FlowPreparation.prepare(platform, lines.toList(), components)
        ""
    }

    @Test
    fun `valid tap followed by invalid command emits no stdout or device work`() {
        val output = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(output))
            val result = result(Platform.Android, "tap_by_coordinates 1 2", "unknown")
            assertEquals(2, result.exitCode)
            assertNull(result.stdout)
            assertEquals("", output.toString())
            assertFalse(components.isInitialized())
        } finally {
            System.setOut(previous)
        }
    }

    @Test
    fun `invalid later argument and platform fail the entire preflight`() {
        for (line in listOf(
            "swipe 0 0 10 10 --steps 0", "tap_by_coordinates wrong 2", "wait_for_element --timeout 1",
            "tap_on_element --text Login --timeout 30001", "press_key 2147483648", "long_press --x 1",
            "input_text hello --timeout 1", "double_tap --x 1 --y 2 --text Login",
        )) {
            assertEquals(2, result(Platform.Android, "press_home", line).exitCode, line)
        }
        for (line in listOf("press_back", "press_key enter", "clear_text")) {
            assertEquals(5, result(Platform.Ios, "press_home", line).exitCode, line)
        }
        for (line in listOf("dismiss_keyboard", "handle_alert accept")) {
            assertEquals(5, result(Platform.Android, "press_home", line).exitCode, line)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `every supported operation prepares without initializing components`() {
        val common = listOf(
            "tap_by_coordinates 0 2", "tap_on_element --text Login", "input_text ''",
            "long_press --x 0 --y 2", "double_tap --text Login", "swipe 0 0 20 20",
            "swipe_direction left", "swipe_on_element right --text Carousel", "press_home",
            "wait_for_element --text Login", "screenshot --output '/tmp/flow shot.png'",
        )
        for (platform in Platform.entries) {
            val specific = if (platform == Platform.Android) listOf("press_back", "press_key enter", "clear_text")
                else listOf("dismiss_keyboard", "handle_alert accept")
            val prepared = FlowPreparation.prepare(platform, common + specific, components)
            assertEquals((common + specific).map { it.substringBefore(' ') }, prepared.map { it.command })
            assertEquals(prepared.indices.map { "step-${it + 1}" }, prepared.map { it.id })
            assertEquals(FlowStepKind.WAIT, prepared[9].kind)
            assertEquals("appeared", prepared[9].condition)
            assertEquals(FlowStepKind.SCREENSHOT, prepared[10].kind)
            assertEquals(FlowStepKind.ACTION, prepared[0].kind)
            assertNull(prepared[0].condition)
        }
        val gone = FlowPreparation.prepare(Platform.Ios, listOf("wait_for_element --text Spinner --gone"), components)
        assertEquals("gone", gone.single().condition)
        assertFalse(components.isInitialized())
    }

    @Test
    fun `reserved options are rejected in option positions`() {
        for (option in listOf("--platform ios", "--platform=ios", "-p ios", "-pios", "-p=ios",
            "--json", "--json=true", "--help", "-h")) {
            assertEquals(2, result(Platform.Android, "press_home $option").exitCode, option)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `reserved words remain valid argument and selector values`() {
        for (word in listOf("--platform", "--json", "--help", "-p", "-h")) {
            for (line in listOf("input_text -- $word", "tap_on_element --text $word",
                "tap_on_element --text=$word", "input_text hello --target-text $word")) {
                assertEquals(0, result(Platform.Android, line).exitCode, line)
            }
        }
        assertEquals(0, result(Platform.Android, "input_text @definitely-not-an-argument-file").exitCode)
        assertFalse(components.isInitialized())
    }

    @Test
    fun `other CLI commands and empty sequences are rejected`() {
        for (line in listOf("flow", "init", "start_automation_server", "get_ui_hierarchy", "find_element",
            "launch_app", "serve", "")) {
            assertEquals(2, result(Platform.Android, line).exitCode, line)
        }
        assertEquals(2, result(Platform.Android).exitCode)
        assertFalse(components.isInitialized())
    }
}
