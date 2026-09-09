package com.example.visiontest.cli.commands

import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.cli.CliCommandRunner
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.ElementSelectorOptions
import com.example.visiontest.cli.IosAppScopeOptions
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.config.AutomationConfig
import com.example.visiontest.ios.IOSElementSelectors
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

class TapOnElementCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "tap_on_element", help = "Tap a selected UI element") {
    private val platform by platformOption()
    private val selectors by ElementSelectorOptions()
    private val appScope by IosAppScopeOptions()
    private val timeout by option(
        "--timeout",
        help = "Max wait in milliseconds (default ${AutomationConfig.ELEMENT_TAP_DEFAULT_TIMEOUT_MS}, " +
            "max ${AutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS})",
    ).int()

    override fun run() = runner {
        selectors.validate()
        appScope.validate(platform)
        val resolvedTimeout = timeout ?: AutomationConfig.ELEMENT_TAP_DEFAULT_TIMEOUT_MS.toInt()
        require(resolvedTimeout in 1..AutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS.toInt()) {
            "--timeout must be between 1 and ${AutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS}"
        }
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.tapOnElement(
                AndroidElementSelectors(
                    text = selectors.text,
                    textContains = selectors.textContains,
                    resourceId = selectors.resourceId,
                    className = selectors.className,
                    contentDescription = selectors.contentDescription,
                ),
                resolvedTimeout,
            )
            Platform.Ios -> components.value.iosAutomationRegistrar.tapOnElement(
                IOSElementSelectors(
                    text = selectors.text,
                    textContains = selectors.textContains,
                    identifier = selectors.resourceId,
                    elementType = selectors.className,
                    label = selectors.contentDescription,
                    bundleId = appScope.bundleId,
                ),
                resolvedTimeout,
            )
        }
    }
}
