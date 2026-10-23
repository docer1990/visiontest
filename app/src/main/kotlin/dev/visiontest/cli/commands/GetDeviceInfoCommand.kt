package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.InspectionCommand
import dev.visiontest.cli.Platform
import dev.visiontest.cli.inspectionOutput
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class GetDeviceInfoCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "get_device_info", help = "Get device display info") {

    private val platform by platformOption()

    private val json by option("--json", help = "Print a structured JSON object").flag()

    override fun run() = runner {
        requireServerRunning { components.value.isServerRunning(platform) }
        val result = when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.getDeviceInfo()
            Platform.Ios -> components.value.iosAutomationRegistrar.getDeviceInfo()
        }
        inspectionOutput(result, json, InspectionCommand.DEVICE_INFO, platform)
    }
}
