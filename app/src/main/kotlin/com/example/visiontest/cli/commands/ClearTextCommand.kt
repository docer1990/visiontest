package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireAndroid
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class ClearTextCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "clear_text", help = "Clear the focused editable field (Android only)") {
    private val platform by androidOnlyPlatformOption()

    override fun run() = runner {
        requireAndroid(platform, "clear_text")
        requireServerRunning { components.value.isServerRunning(platform) }
        components.value.androidAutomationRegistrar.clearText()
    }
}
