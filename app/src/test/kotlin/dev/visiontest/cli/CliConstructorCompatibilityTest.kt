package dev.visiontest.cli

import dev.visiontest.cli.commands.AutomationServerStatusCommand
import dev.visiontest.cli.commands.InitCommand
import dev.visiontest.cli.commands.InstallAutomationServerCommand
import dev.visiontest.cli.commands.LaunchAppCommand
import dev.visiontest.cli.commands.PressBackCommand
import dev.visiontest.cli.commands.PressHomeCommand
import dev.visiontest.cli.commands.ScreenshotCommand
import dev.visiontest.cli.commands.StartAutomationServerCommand
import dev.visiontest.cli.commands.StopAutomationServerCommand
import dev.visiontest.cli.commands.SwipeDirectionCommand
import dev.visiontest.cli.commands.TapByCoordinatesCommand
import com.github.ajalt.clikt.core.CliktCommand
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliConstructorCompatibilityTest {
    @Test
    fun `legacy JVM device command constructors remain callable without initializing components`() {
        val commands = listOf(
            AutomationServerStatusCommand::class.java to "automation_server_status",
            InstallAutomationServerCommand::class.java to "install_automation_server",
            LaunchAppCommand::class.java to "launch_app",
            PressBackCommand::class.java to "press_back",
            PressHomeCommand::class.java to "press_home",
            ScreenshotCommand::class.java to "screenshot",
            StartAutomationServerCommand::class.java to "start_automation_server",
            StopAutomationServerCommand::class.java to "stop_automation_server",
            SwipeDirectionCommand::class.java to "swipe_direction",
            TapByCoordinatesCommand::class.java to "tap_by_coordinates",
        )
        val components = lazy<ComponentHolder> { error("Constructor must not initialize device components") }
        commands.forEach { (commandClass, name) ->
            val command = commandClass.getConstructor(Lazy::class.java).newInstance(components) as CliktCommand
            assertEquals(name, command.commandName)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `legacy JVM init constructor accepts working directory and resource loader`() {
        val resourceLoader: (String) -> String? = { "skill contents" }
        val command = InitCommand::class.java.getConstructor(Path::class.java, Function1::class.java)
            .newInstance(Path.of("."), resourceLoader)
        assertEquals("init", command.commandName)
    }
}
