package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class InfoAppCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "info_app", help = "Get app information") {
    private val platform by platformOption()
    private val id by argument(help = "Package name (Android) / bundle ID (iOS)")
    private val json by option("--json", help = "Print a structured JSON object").flag()

    override fun run() = runner {
        when (platform) {
            Platform.Android -> components.value.androidDeviceRegistrar.infoApp(id, json)
            Platform.Ios -> components.value.iosDeviceRegistrar.infoApp(id, json)
        }
    }
}
