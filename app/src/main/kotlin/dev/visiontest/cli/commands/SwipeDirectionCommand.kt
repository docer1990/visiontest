package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

class SwipeDirectionCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedActionCommand(runner = runner, name = "swipe_direction", help = "Swipe in a direction") {

    private val platform by platformOption()
    private val direction by argument(help = "Swipe direction: up, down, left, right")
        .choice("up", "down", "left", "right")
    private val distance by option("--distance", help = "Swipe distance")
        .choice("short", "medium", "long").default("medium")
    private val speed by option("--speed", help = "Swipe speed")
        .choice("slow", "normal", "fast").default("normal")

    override fun prepareExecution(): suspend () -> String {
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar
                    .swipeByDirection(direction, distance, speed)
                Platform.Ios -> components.value.iosAutomationRegistrar.swipeByDirection(direction, distance, speed)
            }
        }
    }
}
