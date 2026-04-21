package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

class SwipeDirectionCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "swipe_direction", help = "Swipe in a direction") {

    private val platform by platformOption()
    private val direction by argument(help = "Swipe direction: up, down, left, right")
        .choice("up", "down", "left", "right")
    private val distance by option("--distance", help = "Swipe distance")
        .choice("short", "medium", "long").default("medium")
    private val speed by option("--speed", help = "Swipe speed")
        .choice("slow", "normal", "fast").default("normal")

    override fun run() = runCliCommand {
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.swipeByDirection(direction, distance, speed)
            Platform.Ios -> components.value.iosAutomationRegistrar.swipeByDirection(direction, distance, speed)
        }
    }
}
