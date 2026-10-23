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
import com.github.ajalt.clikt.parameters.groups.provideDelegate

class DismissKeyboardCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(
        runner = runner, name = "dismiss_keyboard", help = "Dismiss the visible software keyboard (iOS only)",
    ) {
    private val platform by platformOption()
    private val appScope by IosAppScopeOptions()

    override fun prepareExecution(): suspend () -> String {
        if (platform != Platform.Ios) {
            throw CliExit(ExitCode.PlatformNotSupported, "'dismiss_keyboard' is only supported on iOS.")
        }
        appScope.validate(platform)
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            components.value.iosAutomationRegistrar.dismissKeyboard(appScope.bundleId)
        }
    }
}
