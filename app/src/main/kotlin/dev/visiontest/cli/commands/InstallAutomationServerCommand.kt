package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.androidOnlyPlatformOption
import dev.visiontest.cli.requireAndroid
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.CliCommandRunner
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
