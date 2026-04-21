package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option

class ScreenshotCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "screenshot", help = "Capture a screenshot") {

    private val platform by platformOption()
    private val output by option("--output", help = "Output file path for the screenshot PNG")

    override fun run() = runCliCommand {
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.captureScreenshot(output)
            Platform.Ios -> components.value.iosAutomationRegistrar.captureScreenshot(output)
        }
    }
}
