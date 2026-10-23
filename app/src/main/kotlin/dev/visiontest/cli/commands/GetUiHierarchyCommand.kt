package dev.visiontest.cli.commands

import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.CliCommandRunner
import dev.visiontest.cli.IosAppScopeOptions
import dev.visiontest.cli.Platform
import dev.visiontest.cli.platformOption
import dev.visiontest.cli.requireServerRunning
import dev.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate

class GetUiHierarchyCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) :
    CliktCommand(name = "get_ui_hierarchy", help = "Get full UI hierarchy XML") {

    private val platform by platformOption()
    private val appScope by IosAppScopeOptions()

    override fun run() = runner {
        appScope.validate(platform)
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.getUiHierarchy()
            Platform.Ios -> components.value.iosAutomationRegistrar.getUiHierarchy(appScope.bundleId)
        }
    }
}
