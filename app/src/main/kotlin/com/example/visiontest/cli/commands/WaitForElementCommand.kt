package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.config.AutomationConfig
import com.example.visiontest.ios.IOSElementSelectors
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

/**
 * Exit codes: 0 element found (or gone with `--gone`), 1 timeout, 2 missing selector /
 * timeout above the cap, 3 automation server not running.
 */
class WaitForElementCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "wait_for_element", help = "Wait for a UI element to appear (or disappear with --gone)") {

    private val platform by platformOption()
    private val text by option("--text", help = "Exact text match")
    private val textContains by option("--text-contains", help = "Partial text match")
    private val resourceId by option("--resource-id", help = "Resource ID (Android) / accessibility identifier (iOS)")
    private val className by option("--class-name", help = "Class name (Android) / element type (iOS)")
    private val contentDescription by option(
        "--content-description",
        help = "Content description (Android) / accessibility label (iOS)"
    )
    private val bundleId by option("--bundle-id", help = "Bundle ID of the app to search in (iOS only)")
    private val timeout by option(
        "--timeout",
        help = "Max wait in milliseconds (default ${AutomationConfig.WAIT_DEFAULT_TIMEOUT_MS}, " +
            "max ${AutomationConfig.WAIT_MAX_TIMEOUT_MS})"
    ).int()
    private val gone by option("--gone", help = "Wait for the element to disappear instead").flag()

    override fun run() = runCliCommand {
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> runAndroid()
            Platform.Ios -> runIos()
        }
    }

    private suspend fun runAndroid(): String {
        val registrar = components.value.androidWaitRegistrar
        val selectors = AndroidElementSelectors(
            text = text,
            textContains = textContains,
            resourceId = resourceId,
            className = className,
            contentDescription = contentDescription,
        )
        return if (gone) {
            registrar.waitUntilGone(selectors, timeout)
        } else {
            registrar.waitForElement(selectors, timeout)
        }
    }

    private suspend fun runIos(): String {
        val registrar = components.value.iosWaitRegistrar
        val selectors = IOSElementSelectors(
            text = text,
            textContains = textContains,
            identifier = resourceId,
            elementType = className,
            label = contentDescription,
            bundleId = bundleId,
        )
        return if (gone) {
            registrar.waitUntilGone(selectors, timeout)
        } else {
            registrar.waitForElement(selectors, timeout)
        }
    }
}
