package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument

class InputTextCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "input_text", help = "Type text into focused element") {

    private val platform by platformOption()
    private val text by argument(help = "Text to type")

    override fun run() = runCliCommand {
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.inputText(text)
            Platform.Ios -> components.value.iosAutomationRegistrar.inputText(text)
        }
    }
}
