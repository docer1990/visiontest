package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.CompactInspectionCliOptions
import com.example.visiontest.cli.ComponentHolder
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

class GetInteractiveElementsCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "get_interactive_elements", help = "Get interactive UI elements") {

    private val platform by platformOption()
    private val includeDisabled by option("--include-disabled", help = "Include disabled elements").flag()
    private val appScope by IosAppScopeOptions()

    private val compactInspection by CompactInspectionCliOptions()

    private val json by option("--json", help = "Print a structured JSON object").flag()

    override fun run() = runner {
        val options = compactInspection.options()
        options.validate()
        appScope.validate(platform)
        requireServerRunning { components.value.isServerRunning(platform) }
        val result = when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar
                .getInteractiveElements(includeDisabled, options)
            Platform.Ios -> components.value.iosAutomationRegistrar
                .getInteractiveElements(includeDisabled, appScope.bundleId, options)
        }
        if (options.compact) result
        else inspectionOutput(result, json, InspectionCommand.INTERACTIVE_ELEMENTS, platform)
    }
}
