package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.ElementSelectorOptions
import com.example.visiontest.cli.IosAppScopeOptions
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.ios.IOSElementSelectors
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

class SwipeOnElementCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "swipe_on_element", help = "Swipe inside a selected UI element") {
    private val platform by platformOption()
    private val direction by argument().choice("up", "down", "left", "right")
    private val selectors by ElementSelectorOptions()
    private val appScope by IosAppScopeOptions()
    private val speed by option("--speed", help = "Swipe speed").choice("slow", "normal", "fast").default("normal")

    override fun run() = runner {
        selectors.validate()
        appScope.validate(platform)
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.swipeOnElement(
                direction, selectors.text, selectors.textContains, selectors.resourceId,
                selectors.className, selectors.contentDescription, speed
            )
            Platform.Ios -> components.value.iosAutomationRegistrar.swipeOnElement(
                direction,
                IOSElementSelectors(
                    text = selectors.text,
                    textContains = selectors.textContains,
                    identifier = selectors.resourceId,
                    elementType = selectors.className,
                    label = selectors.contentDescription,
                    bundleId = appScope.bundleId,
                ),
                speed,
            )
        }
    }
}
