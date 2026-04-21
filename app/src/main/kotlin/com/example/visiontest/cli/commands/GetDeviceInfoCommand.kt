package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class GetDeviceInfoCommand(private val components: ComponentHolder) :
    CliktCommand(name = "get_device_info", help = "Get device display info") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        when (platform) {
            "android" -> components.androidAutomationRegistrar.getDeviceInfo()
            "ios" -> components.iosAutomationRegistrar.getDeviceInfo()
            else -> error("Unexpected platform: $platform")
        }
    }
}
