package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.CliExit
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.ExitCode
import dev.visiontest.cli.IosAppScopeOptions
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

class HandleAlertCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(runner = runner, name = "handle_alert", help = "Accept or dismiss an alert (iOS only)") {
    private val platform by platformOption()
    private val action by argument(help = "Alert action").choice("accept", "dismiss")
    private val buttonLabel by option("--button-label", help = "Exact label of the alert button to tap")
    private val appScope by IosAppScopeOptions()

    override fun prepareExecution(): suspend () -> String {
        if (platform != Platform.Ios) {
            throw CliExit(ExitCode.PlatformNotSupported, "'handle_alert' is only supported on iOS.")
        }
        appScope.validate(platform)
        require(buttonLabel?.isNotBlank() != false) { "--button-label must not be blank" }
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            components.value.iosAutomationRegistrar.handleAlert(action, buttonLabel, appScope.bundleId)
        }
    }
}
