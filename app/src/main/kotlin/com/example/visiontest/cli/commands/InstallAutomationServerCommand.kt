package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireAndroid
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.CliCommandRunner
import com.github.ajalt.clikt.core.CliktCommand

class InstallAutomationServerCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "install_automation_server", help = "Install automation server APKs (Android only)") {

    private val platform by androidOnlyPlatformOption()

    override fun run() = runner {
        requireAndroid(platform, "install_automation_server")
        components.value.androidAutomationRegistrar.installAutomationServer()
    }
}
