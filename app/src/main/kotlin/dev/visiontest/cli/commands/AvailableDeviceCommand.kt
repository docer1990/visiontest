package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class AvailableDeviceCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "available_device", help = "Describe the first available device") {
    private val platform by platformOption()
    private val json by option("--json", help = "Print a structured JSON object").flag()

    override fun run() = runner {
        when (platform) {
            Platform.Android -> components.value.androidDeviceRegistrar.availableDevice(json)
            Platform.Ios -> components.value.iosDeviceRegistrar.availableDevice(json)
        }
    }
}
