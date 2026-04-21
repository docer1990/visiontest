package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class PressHomeCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "press_home", help = "Press the home button") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.pressHome()
            Platform.Ios -> components.value.iosAutomationRegistrar.pressHome()
        }
    }
}
