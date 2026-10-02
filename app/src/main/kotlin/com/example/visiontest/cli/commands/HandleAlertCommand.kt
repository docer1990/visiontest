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
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

class HandleAlertCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "handle_alert", help = "Accept or dismiss an alert (iOS only)") {
    private val platform by platformOption()
    private val action by argument(help = "Alert action").choice("accept", "dismiss")
    private val buttonLabel by option("--button-label", help = "Exact label of the alert button to tap")
    private val appScope by IosAppScopeOptions()

    override fun run() = runner {
        if (platform != Platform.Ios) {
            throw CliExit(ExitCode.PlatformNotSupported, "'handle_alert' is only supported on iOS.")
        }
        appScope.validate(platform)
        require(buttonLabel?.isNotBlank() != false) { "--button-label must not be blank" }
        requireServerRunning { components.value.isServerRunning(platform) }
        components.value.iosAutomationRegistrar.handleAlert(action, buttonLabel, appScope.bundleId)
    }
}
