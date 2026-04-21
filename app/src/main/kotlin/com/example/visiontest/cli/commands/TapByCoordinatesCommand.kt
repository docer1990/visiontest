package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int

class TapByCoordinatesCommand(private val components: ComponentHolder) :
    CliktCommand(name = "tap_by_coordinates", help = "Tap at screen coordinates") {

    private val platform by platformOption()
    private val x by argument(help = "X coordinate").int()
    private val y by argument(help = "Y coordinate").int()

    override fun run() = runCliCommand {
        when (platform) {
            "android" -> components.androidAutomationRegistrar.tapByCoordinates(x, y)
            "ios" -> components.iosAutomationRegistrar.tapByCoordinates(x, y)
            else -> error("Unexpected platform: $platform")
        }
    }
}
