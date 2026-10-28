package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.validateAppIdentifier
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument

class LaunchAppCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "launch_app", help = "Launch an app by package/bundle ID") {

    private val platform by platformOption()
    private val id by argument(help = "Package name (Android) or bundle ID (iOS)")

    override fun run() = runner {
        validateAppIdentifier(platform, id)
        when (platform) {
            Platform.Android -> components.value.androidDeviceRegistrar.launchApp(id)
            Platform.Ios -> components.value.iosDeviceRegistrar.launchApp(id)
        }
    }
}
