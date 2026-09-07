package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class ListAppsCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "list_apps", help = "List installed apps") {
    private val platform by platformOption()
    private val json by option("--json", help = "Print a structured JSON object").flag()

    override fun run() = runner {
        when (platform) {
            Platform.Android -> components.value.androidDeviceRegistrar.listApps(json)
            Platform.Ios -> components.value.iosDeviceRegistrar.listApps(json)
        }
    }
}
