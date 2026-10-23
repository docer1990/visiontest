package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.ElementSelectorOptions
import dev.visiontest.cli.IosAppScopeOptions
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import dev.visiontest.ios.IOSElementSelectors
import dev.visiontest.cli.PreparedActionCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

class SwipeOnElementCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) : PreparedActionCommand(runner = runner, name = "swipe_on_element", help = "Swipe inside a selected UI element") {
    private val platform by platformOption()
    private val direction by argument().choice("up", "down", "left", "right")
    private val selectors by ElementSelectorOptions()
    private val appScope by IosAppScopeOptions()
    private val speed by option("--speed", help = "Swipe speed").choice("slow", "normal", "fast").default("normal")

    override fun prepareExecution(): suspend () -> String {
        selectors.validate()
        appScope.validate(platform)
        return {
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
}
