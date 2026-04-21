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
 * A [ComponentHolder] is created lazily on first subcommand execution so that
 * `visiontest --help` does not initialize ADB connections or register shutdown hooks.
 */
class VisionTestCli : NoOpCliktCommand(name = "visiontest") {
    private val components by lazy { ComponentHolder.createDefault() }

    init {
        subcommands(
            // Setup
            InstallAutomationServerCommand(lazy { components }),
            StartAutomationServerCommand(lazy { components }),
            AutomationServerStatusCommand(lazy { components }),
            // Inspection
            GetInteractiveElementsCommand(lazy { components }),
            GetUiHierarchyCommand(lazy { components }),
            GetDeviceInfoCommand(lazy { components }),
            ScreenshotCommand(lazy { components }),
            // Interaction
            TapByCoordinatesCommand(lazy { components }),
            InputTextCommand(lazy { components }),
            SwipeDirectionCommand(lazy { components }),
            // Navigation
            PressBackCommand(lazy { components }),
            PressHomeCommand(lazy { components }),
            // Apps
            LaunchAppCommand(lazy { components }),
        )
    }
}
