package dev.visiontest.cli.commands

import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.android.AndroidElementSelectors
import dev.visiontest.config.AutomationConfig
import dev.visiontest.ios.IOSElementSelectors
import dev.visiontest.cli.waitOutput
import dev.visiontest.cli.PreparedCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

/**
 * Exit codes: 0 element found (or gone with `--gone`), 1 timeout, 2 missing selector /
 * timeout above the cap, 3 automation server not running.
 */
class WaitForElementCommand(
    private val components: Lazy<ComponentHolder>,
    runner: CliCommandRunner = ::runCliCommand,
) :
    PreparedCommand(
        runner = runner, name = "wait_for_element",
        help = "Wait for a UI element to appear (or disappear with --gone)",
    ) {

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

    private val json by option("--json", help = "Output the wait result as JSON").flag()

    override fun formatStandaloneOutput(response: String): String = waitOutput(response, json, platform, gone)

    internal val flowCondition: String get() = if (gone) "gone" else "appeared"

    override fun prepareExecution(): suspend () -> String {
        validateArguments()
        return {
            requireServerRunning { components.value.isServerRunning(platform) }
            when (platform) {
                Platform.Android -> runAndroid()
                Platform.Ios -> runIos()
            }
        }
    }

    private fun validateArguments() {
        val selectors = listOf(text, textContains, resourceId, className, contentDescription)
        require(selectors.any { !it.isNullOrBlank() }) {
            "At least one selector required (text, textContains, resourceId, className, or contentDescription)"
        }
        require(selectors.filterNotNull().all { it.isNotBlank() }) { "Element selectors must not be blank" }
        require(timeout == null || timeout in 1..AutomationConfig.WAIT_MAX_TIMEOUT_MS.toInt()) {
            "timeoutMs must be between 1 and ${AutomationConfig.WAIT_MAX_TIMEOUT_MS}, got $timeout"
        }
        require(platform == Platform.Ios || bundleId == null) { "--bundle-id is only supported on iOS" }
        require(bundleId?.isNotBlank() != false) { "--bundle-id must not be blank" }
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
        return registrar.waitResult(selectors, timeout, expectGone = gone)
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
        return registrar.waitResult(selectors, timeout, expectGone = gone)
    }
}
