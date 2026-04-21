package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireAndroid
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class InstallAutomationServerCommand(private val components: ComponentHolder) :
    CliktCommand(name = "install_automation_server", help = "Install automation server APKs (Android only)") {

    private val platform by androidOnlyPlatformOption()

    override fun run() {
        requireAndroid(platform)
        runCliCommand { components.androidAutomationRegistrar.installAutomationServer() }
    }
}
