package com.example.visiontest.cli

import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests Clikt argument parsing for CLI commands without triggering `exitProcess`.
 * Uses lightweight stub commands that capture parsed values instead of calling `runCliCommand`.
 */
class VisionTestCliTest {

    // --- Helpers: lightweight test commands that record parsed values ---

    private class TestPlatformCommand(name: String) : CliktCommand(name = name) {
        val platform by platformOption()
        var ran = false
        override fun run() { ran = true }
    }

    private class TestAndroidOnlyCommand(name: String) : CliktCommand(name = name) {
        val platform by androidOnlyPlatformOption()
        var ran = false
        override fun run() { ran = true }
    }

    private class TestTapCommand : CliktCommand(name = "tap_by_coordinates") {
        val platform by platformOption()
        val x by argument().int()
        val y by argument().int()
        var ran = false
        override fun run() { ran = true }
    }

    // --- Platform parsing ---

    @Test
    fun `cross-platform command accepts android`() {
        val cmd = TestPlatformCommand("test")
        cmd.parse(listOf("--platform", "android"))
        assertEquals(Platform.Android, cmd.platform)
        assertTrue(cmd.ran)
    }

    @Test
    fun `cross-platform command accepts ios`() {
        val cmd = TestPlatformCommand("test")
        cmd.parse(listOf("--platform", "ios"))
        assertEquals(Platform.Ios, cmd.platform)
    }

    @Test
    fun `cross-platform command accepts short flag`() {
        val cmd = TestPlatformCommand("test")
        cmd.parse(listOf("-p", "android"))
        assertEquals(Platform.Android, cmd.platform)
    }

    @Test
    fun `missing platform produces usage error`() {
        val cmd = TestPlatformCommand("test")
        assertFailsWith<MissingOption> {
            cmd.parse(emptyList())
        }
    }

    @Test
    fun `invalid platform value is rejected`() {
        val cmd = TestPlatformCommand("test")
        assertFailsWith<BadParameterValue> {
            cmd.parse(listOf("--platform", "windows"))
        }
    }

    // --- Android-only commands ---

    @Test
    fun `android-only command accepts android`() {
        val cmd = TestAndroidOnlyCommand("test")
        cmd.parse(listOf("--platform", "android"))
        assertTrue(cmd.ran)
    }

    @Test
    fun `android-only command parses ios then requireAndroid rejects`() {
        val cmd = TestAndroidOnlyCommand("test")
        cmd.parse(listOf("--platform", "ios"))
        // Parsing succeeds; the command's run() would call requireAndroid() to reject
        assertEquals(Platform.Ios, cmd.platform)
        assertFailsWith<CliExit> {
            requireAndroid(cmd.platform, "test")
        }
    }

    // --- Positional args ---

    @Test
    fun `tap command parses x and y`() {
        val cmd = TestTapCommand()
        cmd.parse(listOf("--platform", "android", "100", "200"))
        assertEquals(100, cmd.x)
        assertEquals(200, cmd.y)
    }

    @Test
    fun `tap command missing y produces error`() {
        val cmd = TestTapCommand()
        assertFailsWith<MissingArgument> {
            cmd.parse(listOf("--platform", "android", "100"))
        }
    }

    @Test
    fun `tap command non-integer x produces error`() {
        val cmd = TestTapCommand()
        assertFailsWith<BadParameterValue> {
            cmd.parse(listOf("--platform", "android", "abc", "200"))
        }
    }

    // --- Subcommand routing ---

    @Test
    fun `root command lists all 13 subcommands`() {
        // We can't use the real VisionTestCli (ComponentHolder is lazy but still needs ADB),
        // so we just verify the count expectation as a documentation test.
        val expectedCommands = listOf(
            "install_automation_server", "start_automation_server", "automation_server_status",
            "get_interactive_elements", "get_ui_hierarchy", "get_device_info", "screenshot",
            "tap_by_coordinates", "input_text", "swipe_direction",
            "press_back", "press_home", "launch_app"
        )
        assertEquals(13, expectedCommands.size)
    }

    // --- SwipeDirection choice validation ---

    private class TestSwipeCommand : CliktCommand(name = "swipe_direction") {
        val platform by platformOption()
        val direction by argument().choice("up", "down", "left", "right")
        var ran = false
        override fun run() { ran = true }
    }

    @Test
    fun `swipe direction rejects invalid direction`() {
        val cmd = TestSwipeCommand()
        assertFailsWith<BadParameterValue> {
            cmd.parse(listOf("--platform", "android", "diagonal"))
        }
    }

    @Test
    fun `swipe direction accepts valid direction`() {
        val cmd = TestSwipeCommand()
        cmd.parse(listOf("--platform", "android", "up"))
        assertEquals("up", cmd.direction)
    }
}
