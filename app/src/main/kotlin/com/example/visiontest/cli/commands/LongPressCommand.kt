package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.InteractionTargetOptions
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate

class LongPressCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(runner = runner, name = "long_press", help = "Long press at coordinates or on a selected element") {
    private val platform by platformOption()
    private val target by InteractionTargetOptions()

    override fun prepareExecution(): suspend () -> String {
        target.validate(platform)
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar.longPress(
                    target.x, target.y, target.androidSelectors(), target.timeout,
                )
                Platform.Ios -> components.value.iosAutomationRegistrar.longPress(
                    target.x, target.y, target.iosSelectors(target.bundleId), target.timeout,
                )
            }
        }
    }
}
