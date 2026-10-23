package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.androidOnlyPlatformOption
import dev.visiontest.cli.requireAndroid
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.PreparedActionCommand

class ClearTextCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(
    runner = runner, name = "clear_text", help = "Clear the focused editable field (Android only)",
) {
    private val platform by androidOnlyPlatformOption()

    override fun prepareExecution(): suspend () -> String {
        requireAndroid(platform, "clear_text")
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            components.value.androidAutomationRegistrar.clearText()
        }
    }
}
