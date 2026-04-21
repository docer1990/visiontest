package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand

class GetUiHierarchyCommand(private val components: ComponentHolder) :
    CliktCommand(name = "get_ui_hierarchy", help = "Get full UI hierarchy XML") {

    private val platform by platformOption()

    override fun run() = runCliCommand {
        when (platform) {
            "android" -> components.androidAutomationRegistrar.getUiHierarchy()
            "ios" -> components.iosAutomationRegistrar.getUiHierarchy()
            else -> error("Unexpected platform: $platform")
        }
    }
}
