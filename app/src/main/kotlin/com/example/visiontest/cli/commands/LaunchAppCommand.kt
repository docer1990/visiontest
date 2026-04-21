package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument

class LaunchAppCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "launch_app", help = "Launch an app by package/bundle ID") {

    private val platform by platformOption()
    private val id by argument(help = "Package name (Android) or bundle ID (iOS)")

    override fun run() = runCliCommand {
        when (platform) {
            Platform.Android -> components.value.androidDeviceRegistrar.launchApp(id)
            Platform.Ios -> components.value.iosDeviceRegistrar.launchApp(id)
        }
    }
}
