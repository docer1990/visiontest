package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.ElementSelectorOptions
import com.example.visiontest.cli.InspectionCommand
import com.example.visiontest.cli.IosAppScopeOptions
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.inspectionOutput
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class FindElementCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "find_element", help = "Find a UI element by selectors") {
    private val platform by platformOption()
    private val selectors by ElementSelectorOptions()
    private val appScope by IosAppScopeOptions()
    private val json by option("--json", help = "Print a structured JSON object").flag()

    override fun run() = runner {
        selectors.validate()
        appScope.validate(platform)
        requireServerRunning { components.value.isServerRunning(platform) }
        val result = when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.findElement(
                selectors.text, selectors.textContains, selectors.resourceId,
                selectors.className, selectors.contentDescription
            )
            Platform.Ios -> components.value.iosAutomationRegistrar.findElement(
                selectors.text, selectors.textContains, selectors.resourceId,
                selectors.className, selectors.contentDescription, appScope.bundleId
            )
        }
        inspectionOutput(result, json, InspectionCommand.FIND_ELEMENT, platform)
    }
}
