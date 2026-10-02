package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.CliExit
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.ExitCode
import com.example.visiontest.cli.IosAppScopeOptions
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate

class DismissKeyboardCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "dismiss_keyboard", help = "Dismiss the visible software keyboard (iOS only)") {
    private val platform by platformOption()
    private val appScope by IosAppScopeOptions()

    override fun run() = runner {
        if (platform != Platform.Ios) {
            throw CliExit(ExitCode.PlatformNotSupported, "'dismiss_keyboard' is only supported on iOS.")
        }
        appScope.validate(platform)
        requireServerRunning { components.value.isServerRunning(platform) }
        components.value.iosAutomationRegistrar.dismissKeyboard(appScope.bundleId)
    }
}
