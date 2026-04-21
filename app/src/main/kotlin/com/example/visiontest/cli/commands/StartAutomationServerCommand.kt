package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class StartAutomationServerCommand(private val components: ComponentHolder) :
    CliktCommand(name = "start_automation_server", help = "Start the automation server") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        when (platform) {
            "android" -> components.androidAutomationRegistrar.startAutomationServer()
            "ios" -> components.iosAutomationRegistrar.startAutomationServer()
            else -> error("Unexpected platform: $platform")
        }
    }
}
