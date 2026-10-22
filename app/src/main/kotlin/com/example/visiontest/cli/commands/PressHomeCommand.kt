package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.PreparedActionCommand

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
