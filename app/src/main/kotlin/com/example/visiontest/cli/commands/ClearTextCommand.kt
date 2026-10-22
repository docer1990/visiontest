package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireAndroid
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.PreparedActionCommand

class ClearTextCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(runner = runner, name = "clear_text", help = "Clear the focused editable field (Android only)") {
    private val platform by androidOnlyPlatformOption()

    override fun prepareExecution(): suspend () -> String {
        requireAndroid(platform, "clear_text")
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            components.value.androidAutomationRegistrar.clearText()
        }
    }
}
