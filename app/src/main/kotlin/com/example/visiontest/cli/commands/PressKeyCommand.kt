package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireAndroid
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.tools.validateKeyInput
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument

class PressKeyCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "press_key", help = "Press an Android key code or named action") {
    private val platform by androidOnlyPlatformOption()
    private val key by argument(help = "Unsigned key code or enter, tab, backspace, delete, escape")

    override fun run() = runner {
        requireAndroid(platform, "press_key")
        val keyCode = if (key.matches(Regex("[0-9]+"))) {
            requireNotNull(key.toIntOrNull()) { "Key code must be a nonnegative 32-bit integer" }
        } else null
        val action = key.takeIf { keyCode == null }
        validateKeyInput(keyCode, action)
        requireServerRunning { components.value.isServerRunning(platform) }
        components.value.androidAutomationRegistrar.pressKey(keyCode, action)
    }
}
