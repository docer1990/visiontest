package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.CliCommandRunner
import com.github.ajalt.clikt.core.CliktCommand

class AutomationServerStatusCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "automation_server_status", help = "Check automation server status") {

    private val platform by platformOption()

    override fun run() = runner {
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.automationServerStatus()
            Platform.Ios -> components.value.iosAutomationRegistrar.automationServerStatus()
        }
    }
}
