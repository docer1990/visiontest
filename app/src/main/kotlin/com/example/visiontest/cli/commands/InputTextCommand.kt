package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.IosAppScopeOptions
import com.example.visiontest.cli.InputTextTargetOptions
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.arguments.argument

class InputTextCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedActionCommand(runner = runner, name = "input_text", help = "Type text into the focused or selected element") {

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
