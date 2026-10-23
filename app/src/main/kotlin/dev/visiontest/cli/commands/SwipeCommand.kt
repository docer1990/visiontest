package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

class SwipeCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(runner = runner, name = "swipe", help = "Swipe between screen coordinates") {
    private companion object {
        const val DEFAULT_STEPS = 20
    }

    private val platform by platformOption()
    private val startX by argument().int()
    private val startY by argument().int()
    private val endX by argument().int()
    private val endY by argument().int()
    private val steps by option("--steps", help = "Positive swipe steps (default $DEFAULT_STEPS)")
        .int().default(DEFAULT_STEPS)

    override fun prepareExecution(): suspend () -> String {
        require(steps > 0) { "--steps must be positive" }
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar.swipe(startX, startY, endX, endY, steps)
                Platform.Ios -> components.value.iosAutomationRegistrar.swipe(startX, startY, endX, endY, steps)
            }
        }
    }
}
