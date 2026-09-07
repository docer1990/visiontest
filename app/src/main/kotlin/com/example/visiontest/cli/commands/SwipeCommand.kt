package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

class SwipeCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "swipe", help = "Swipe between screen coordinates") {
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

    override fun run() = runner {
        require(steps > 0) { "--steps must be positive" }
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.swipe(startX, startY, endX, endY, steps)
            Platform.Ios -> components.value.iosAutomationRegistrar.swipe(startX, startY, endX, endY, steps)
        }
    }
}
