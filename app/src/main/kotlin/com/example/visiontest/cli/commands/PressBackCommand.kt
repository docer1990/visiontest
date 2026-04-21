package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class PressBackCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "press_back", help = "Press the back button (Android only)") {

    private val platform by androidOnlyPlatformOption()

    override fun run() = runCliCommand {
        // platform is guaranteed to be Android by androidOnlyPlatformOption()
        requireServerRunning { components.value.isServerRunning(platform) }
        components.value.androidAutomationRegistrar.pressBack()
    }
}
