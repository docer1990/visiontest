package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument

class InputTextCommand(private val components: ComponentHolder) :
    CliktCommand(name = "input_text", help = "Type text into focused element") {

    private val platform by platformOption()
    private val text by argument(help = "Text to type")

    override fun run() = runCliCommand {
        when (platform) {
            "android" -> components.androidAutomationRegistrar.inputText(text)
            "ios" -> components.iosAutomationRegistrar.inputText(text)
            else -> error("Unexpected platform: $platform")
        }
    }
}
