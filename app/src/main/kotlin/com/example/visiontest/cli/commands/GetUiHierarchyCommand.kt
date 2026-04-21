package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.Platform
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.requireServerRunning
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class GetUiHierarchyCommand(private val components: Lazy<ComponentHolder>) :
    CliktCommand(name = "get_ui_hierarchy", help = "Get full UI hierarchy XML") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.getUiHierarchy()
            Platform.Ios -> components.value.iosAutomationRegistrar.getUiHierarchy()
        }
    }
}
