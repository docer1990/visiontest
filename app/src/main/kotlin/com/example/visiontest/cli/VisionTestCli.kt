package com.example.visiontest.cli

import com.example.visiontest.cli.commands.*
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Root command for the `visiontest` CLI. Dispatches to per-operation subcommands.
 *
 * The JAR's `main(args)` enters this command only when invoked with arguments that
 * are not the MCP stdio sentinel (empty args or `serve`). See [com.example.visiontest.main].
 *
 * A [ComponentHolder] is created once to provide the same dependency graph as the MCP path.
 */
class VisionTestCli : NoOpCliktCommand(name = "visiontest") {
    init {
        val components = ComponentHolder.createDefault()
        subcommands(
            // Setup
            InstallAutomationServerCommand(components),
            StartAutomationServerCommand(components),
            AutomationServerStatusCommand(components),
            // Inspection
            GetInteractiveElementsCommand(components),
            GetUiHierarchyCommand(components),
            GetDeviceInfoCommand(components),
            ScreenshotCommand(components),
            // Interaction
            TapByCoordinatesCommand(components),
            InputTextCommand(components),
            SwipeDirectionCommand(components),
            // Navigation
            PressBackCommand(components),
            PressHomeCommand(components),
            // Apps
            LaunchAppCommand(components),
        )
    }
}
