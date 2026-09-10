package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.InspectionCommand
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.inspectionOutput
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
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
