package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.PreparedCommand
import com.github.ajalt.clikt.parameters.options.option
import com.google.gson.JsonObject
import java.util.UUID

class ScreenshotCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedCommand(runner = runner, name = "screenshot", help = "Capture a screenshot") {

    private val platform by platformOption()
    private val output by option("--output", help = "Output file path for the screenshot PNG")

    override fun prepareExecution(): suspend () -> String {
        val raw = preparingForFlow
        val outputPath = if (raw && output.isNullOrBlank()) {
            "screenshots/${platform.value}_flow_${UUID.randomUUID()}.png"
        } else output
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            val result = when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar.captureScreenshotResult(outputPath)
                Platform.Ios -> components.value.iosAutomationRegistrar.captureScreenshotResult(outputPath)
            }
            if (raw) JsonObject().apply {
                addProperty("success", result.success)
                if (result.success) addProperty("path", result.path) else addProperty("error", result.message)
            }.toString() else result.message
        }
    }
}
