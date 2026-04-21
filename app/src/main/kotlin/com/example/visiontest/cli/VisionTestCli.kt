package com.example.visiontest.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Root command for the `visiontest` CLI. Dispatches to per-operation subcommands.
 *
 * The JAR's `main(args)` enters this command only when invoked with arguments that
 * are not the MCP stdio sentinel (empty args or `serve`). See [com.example.visiontest.main].
 *
 * Phase 4 will replace the stubs with real implementations; the stubs exist here
 * so that Phase 3 compiles and `--help` already lists all 13 commands.
 */
class VisionTestCli : NoOpCliktCommand(name = "visiontest") {
    init {
        subcommands(
            // Setup
            StubCommand("install_automation_server", "Install automation server APKs (Android only)"),
            StubCommand("start_automation_server", "Start the automation server"),
            StubCommand("automation_server_status", "Check automation server status"),
            // Inspection
            StubCommand("get_interactive_elements", "Get interactive UI elements"),
            StubCommand("get_ui_hierarchy", "Get full UI hierarchy XML"),
            StubCommand("get_device_info", "Get device display info"),
            StubCommand("screenshot", "Capture a screenshot"),
            // Interaction
            StubCommand("tap_by_coordinates", "Tap at screen coordinates"),
            StubCommand("input_text", "Type text into focused element"),
            StubCommand("swipe_direction", "Swipe in a direction"),
            // Navigation
            StubCommand("press_back", "Press the back button (Android only)"),
            StubCommand("press_home", "Press the home button"),
            // Apps
            StubCommand("launch_app", "Launch an app by package/bundle ID"),
        )
    }
}

/**
 * Temporary stub subcommand. Replaced in Phase 4 with real implementations.
 */
private class StubCommand(name: String, help: String) : CliktCommand(name = name, help = help) {
    override fun run() {
        echo("Not yet implemented: $commandName")
    }
}
