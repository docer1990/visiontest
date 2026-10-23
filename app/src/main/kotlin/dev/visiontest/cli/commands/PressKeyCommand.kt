package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.androidOnlyPlatformOption
import dev.visiontest.cli.requireAndroid
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.tools.validateKeyInput
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.arguments.argument

class PressKeyCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(runner = runner, name = "press_key", help = "Press an Android key code or named action") {
    private val platform by androidOnlyPlatformOption()
    private val key by argument(help = "Unsigned key code or enter, tab, backspace, delete, escape")

    override fun prepareExecution(): suspend () -> String {
        requireAndroid(platform, "press_key")
        val keyCode = if (key.matches(Regex("[0-9]+"))) {
            requireNotNull(key.toIntOrNull()) { "Key code must be a nonnegative 32-bit integer" }
        } else null
        val action = key.takeIf { keyCode == null }
        validateKeyInput(keyCode, action)
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            components.value.androidAutomationRegistrar.pressKey(keyCode, action)
        }
    }
}
