package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.InteractionTargetOptions
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate

class DoubleTapCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(
    runner = runner, name = "double_tap", help = "Double tap at coordinates or on a selected element",
) {
    private val platform by platformOption()
    private val target by InteractionTargetOptions()

    override fun prepareExecution(): suspend () -> String {
        target.validate(platform)
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar.doubleTap(
                    target.x, target.y, target.androidSelectors(), target.timeout,
                )
                Platform.Ios -> components.value.iosAutomationRegistrar.doubleTap(
                    target.x, target.y, target.iosSelectors(target.bundleId), target.timeout,
                )
            }
        }
    }
}
