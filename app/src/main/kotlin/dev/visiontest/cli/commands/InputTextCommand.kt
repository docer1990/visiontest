package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.IosAppScopeOptions
import dev.visiontest.cli.InputTextTargetOptions
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.arguments.argument

class InputTextCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedActionCommand(
        runner = runner, name = "input_text", help = "Type text into the focused or selected element",
    ) {

    private val platform by platformOption()
    private val text by argument(help = "Text to type")
    private val appScope by IosAppScopeOptions()
    private val target by InputTextTargetOptions()

    override fun prepareExecution(): suspend () -> String {
        appScope.validate(platform)
        target.validate()
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> components.value.androidAutomationRegistrar.inputText(
                    text, target.androidSelectors(), target.timeout,
                )
                Platform.Ios -> components.value.iosAutomationRegistrar.inputText(
                    text, appScope.bundleId, target.iosSelectors(appScope.bundleId), target.timeout,
                )
            }
        }
    }
}
