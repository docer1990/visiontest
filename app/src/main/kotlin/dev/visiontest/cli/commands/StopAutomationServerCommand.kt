package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.runCliCommand
import dev.visiontest.cli.CliCommandRunner
import com.github.ajalt.clikt.core.CliktCommand

/**
 * Idempotent by design: exits 0 both when the server was stopped and when it was
 * not running, so scripts can always call it during teardown. No `requireServerRunning`
 * pre-check — that would defeat the idempotency.
 */
class StopAutomationServerCommand @JvmOverloads constructor(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "stop_automation_server", help = "Stop the automation server") {

    private val platform by platformOption()

    override fun run() = runner {
        when (platform) {
            Platform.Android -> components.value.androidStopRegistrar.stopAutomationServer()
            Platform.Ios -> components.value.iosAutomationRegistrar.stopAutomationServer()
        }
    }
}
