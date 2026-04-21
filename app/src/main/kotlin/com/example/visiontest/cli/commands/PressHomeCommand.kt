package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class PressHomeCommand(private val components: ComponentHolder) :
    CliktCommand(name = "press_home", help = "Press the home button") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        requireServerRunning { components.isServerRunning(platform) }
        when (platform) {
            "android" -> components.androidAutomationRegistrar.pressHome()
            "ios" -> components.iosAutomationRegistrar.pressHome()
            else -> error("Unexpected platform: $platform")
        }
    }
}
