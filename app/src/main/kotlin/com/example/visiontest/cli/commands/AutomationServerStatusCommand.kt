package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class AutomationServerStatusCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "automation_server_status", help = "Check automation server status") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.automationServerStatus()
            Platform.Ios -> components.value.iosAutomationRegistrar.automationServerStatus()
        }
    }
}
