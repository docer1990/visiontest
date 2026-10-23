package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int

class TapByCoordinatesCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedActionCommand(runner = runner, name = "tap_by_coordinates", help = "Tap at screen coordinates") {

    private val platform by platformOption()
    private val x by argument(help = "X coordinate").int()
    private val y by argument(help = "Y coordinate").int()

    override fun prepareExecution(): suspend () -> String {
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar.tapByCoordinates(x, y)
                Platform.Ios -> components.value.iosAutomationRegistrar.tapByCoordinates(x, y)
            }
        }
    }
}
