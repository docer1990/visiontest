package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireAndroid
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.CliCommandRunner
import com.github.ajalt.clikt.core.CliktCommand

class PressBackCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "press_back", help = "Press the back button (Android only)") {

    private val platform by androidOnlyPlatformOption()

    override fun run() = runner {
        requireAndroid(platform, "press_back")
        requireServerRunning { components.value.isServerRunning(platform) }
        components.value.androidAutomationRegistrar.pressBack()
    }
}
