package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class GetInteractiveElementsCommand(private val components: ComponentHolder) :
    CliktCommand(name = "get_interactive_elements", help = "Get interactive UI elements") {

    private val platform by platformOption()
    private val includeDisabled by option("--include-disabled", help = "Include disabled elements").flag()

    override fun run() = runCliCommand {
        when (platform) {
            "android" -> components.androidAutomationRegistrar.getInteractiveElements(includeDisabled)
            "ios" -> components.iosAutomationRegistrar.getInteractiveElements(includeDisabled)
            else -> error("Unexpected platform: $platform")
        }
    }
}
