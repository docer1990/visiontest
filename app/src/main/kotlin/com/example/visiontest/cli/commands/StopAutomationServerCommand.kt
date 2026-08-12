package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

/**
 * Idempotent by design: exits 0 both when the server was stopped and when it was
 * not running, so scripts can always call it during teardown. No `requireServerRunning`
 * pre-check — that would defeat the idempotency.
 */
class StopAutomationServerCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "stop_automation_server", help = "Stop the automation server") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        when (platform) {
            Platform.Android -> components.value.androidStopRegistrar.stopAutomationServer()
            Platform.Ios -> components.value.iosAutomationRegistrar.stopAutomationServer()
        }
    }
}
