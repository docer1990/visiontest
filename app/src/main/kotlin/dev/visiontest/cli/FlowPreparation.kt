package dev.visiontest.cli

import dev.visiontest.cli.commands.ClearTextCommand
import dev.visiontest.cli.commands.DismissKeyboardCommand
import dev.visiontest.cli.commands.DoubleTapCommand
import dev.visiontest.cli.commands.HandleAlertCommand
import dev.visiontest.cli.commands.InputTextCommand
import dev.visiontest.cli.commands.LongPressCommand
import dev.visiontest.cli.commands.PressBackCommand
import dev.visiontest.cli.commands.PressHomeCommand
import dev.visiontest.cli.commands.PressKeyCommand
import dev.visiontest.cli.commands.ScreenshotCommand
import dev.visiontest.cli.commands.SwipeCommand
import dev.visiontest.cli.commands.SwipeDirectionCommand
import dev.visiontest.cli.commands.SwipeOnElementCommand
import dev.visiontest.cli.commands.TapByCoordinatesCommand
import dev.visiontest.cli.commands.TapOnElementCommand
import dev.visiontest.cli.commands.WaitForElementCommand

internal enum class FlowStepKind { ACTION, WAIT, SCREENSHOT }

internal data class FlowPreparedStep(
    val id: String,
    val command: String,
    val kind: FlowStepKind,
    val condition: String? = null,
    val execute: suspend () -> String,
)

internal object FlowPreparation {
    fun prepare(platform: Platform, lines: List<String>, components: Lazy<ComponentHolder>): List<FlowPreparedStep> {
        require(lines.isNotEmpty()) { "Flow must contain at least one step" }
        return lines.mapIndexed { index, line ->
            val tokens = FlowInput.tokenize(line)
            require(tokens.isNotEmpty()) { "Flow step must contain a command" }
            val command = createCommand(tokens.first(), components)
            val args = tokens.drop(1)
            rejectReservedOptions(command, args)
            val execute = command.prepareForFlow(listOf("--platform", platform.value) + args)
            FlowPreparedStep(
                id = "step-${index + 1}",
                command = command.commandName,
                kind = when (command) {
                    is WaitForElementCommand -> FlowStepKind.WAIT
                    is ScreenshotCommand -> FlowStepKind.SCREENSHOT
                    else -> FlowStepKind.ACTION
                },
                condition = (command as? WaitForElementCommand)?.flowCondition,
                execute = execute,
            )
        }
    }

    private val factories: Map<String, (Lazy<ComponentHolder>) -> PreparedCommand> = mapOf(
        "tap_by_coordinates" to { TapByCoordinatesCommand(it) },
        "tap_on_element" to { TapOnElementCommand(it) },
        "input_text" to { InputTextCommand(it) },
        "long_press" to { LongPressCommand(it) },
        "double_tap" to { DoubleTapCommand(it) },
        "swipe" to { SwipeCommand(it) },
        "swipe_direction" to { SwipeDirectionCommand(it) },
        "swipe_on_element" to { SwipeOnElementCommand(it) },
        "press_home" to { PressHomeCommand(it) },
        "wait_for_element" to { WaitForElementCommand(it) },
        "screenshot" to { ScreenshotCommand(it) },
        "press_back" to { PressBackCommand(it) },
        "press_key" to { PressKeyCommand(it) },
        "clear_text" to { ClearTextCommand(it) },
        "dismiss_keyboard" to { DismissKeyboardCommand(it) },
        "handle_alert" to { HandleAlertCommand(it) },
    )

    private fun createCommand(name: String, components: Lazy<ComponentHolder>): PreparedCommand =
        requireNotNull(factories[name]) { "Command '$name' is not supported in flows" }(components)

    private fun rejectReservedOptions(command: PreparedCommand, args: List<String>) {
        val options = command.registeredOptions().flatMap { option ->
            (option.names + option.secondaryNames).map { it to option }
        }.toMap()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            if (token == "--") break
            val name = token.substringBefore('=')
            require(name !in setOf("--platform", "--json", "--help") &&
                !token.startsWith("-p") && !token.startsWith("-h")) {
                "Step-level platform, output, and help options are not supported: $token"
            }
            val option = options[name]
            index += 1 + if (option != null && '=' !in token && option.acceptsUnattachedValue) {
                option.nvalues.last
            } else 0
        }
    }
}
