package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.androidOnlyPlatformOption
import com.example.visiontest.cli.requireAndroid
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.PreparedCommand

class PressBackCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedCommand(runner = runner, name = "press_back", help = "Press the back button (Android only)") {

    private val platform by androidOnlyPlatformOption()

    override fun prepareExecution(): suspend () -> String {
        requireAndroid(platform, "press_back")
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            components.value.androidAutomationRegistrar.pressBack()
        }
    }
}
