package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class AutomationServerStatusCommand(private val components: ComponentHolder) :
    CliktCommand(name = "automation_server_status", help = "Check automation server status") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        when (platform) {
            "android" -> components.androidAutomationRegistrar.automationServerStatus()
            "ios" -> components.iosAutomationRegistrar.automationServerStatus()
            else -> error("Unexpected platform: $platform")
        }
    }
}
