package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.CliCommandRunner
import com.github.ajalt.clikt.core.CliktCommand

class StartAutomationServerCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "start_automation_server", help = "Start the automation server") {

    private val platform by platformOption()

    override fun run() = runner {
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.startAutomationServer()
            Platform.Ios -> components.value.iosAutomationRegistrar.startAutomationServer()
        }
    }
}
