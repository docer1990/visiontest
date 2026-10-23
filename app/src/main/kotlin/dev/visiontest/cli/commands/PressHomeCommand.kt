package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.PreparedActionCommand

class PressHomeCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedActionCommand(runner = runner, name = "press_home", help = "Press the home button") {

    private val platform by platformOption()

    override fun prepareExecution(): suspend () -> String {
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar.pressHome()
                Platform.Ios -> components.value.iosAutomationRegistrar.pressHome()
            }
        }
    }
}
