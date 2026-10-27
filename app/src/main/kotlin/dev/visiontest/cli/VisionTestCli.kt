package dev.visiontest.cli

import dev.visiontest.cli.commands.AutomationServerStatusCommand
import dev.visiontest.cli.commands.AvailableDeviceCommand
import dev.visiontest.cli.commands.ClearTextCommand
import dev.visiontest.cli.commands.DismissKeyboardCommand
import dev.visiontest.cli.commands.DoubleTapCommand
import dev.visiontest.cli.commands.FindElementCommand
import dev.visiontest.cli.commands.FlowCommand
import dev.visiontest.cli.commands.GetDeviceInfoCommand
import dev.visiontest.cli.commands.GetInteractiveElementsCommand
import dev.visiontest.cli.commands.GetUiHierarchyCommand
import dev.visiontest.cli.commands.HandleAlertCommand
import dev.visiontest.cli.commands.InfoAppCommand
import dev.visiontest.cli.commands.InitCommand
import dev.visiontest.cli.commands.InputTextCommand
import dev.visiontest.cli.commands.InstallAutomationServerCommand
import dev.visiontest.cli.commands.LaunchAppCommand
import dev.visiontest.cli.commands.ListAppsCommand
import dev.visiontest.cli.commands.LongPressCommand
import dev.visiontest.cli.commands.PressBackCommand
import dev.visiontest.cli.commands.PressHomeCommand
import dev.visiontest.cli.commands.PressKeyCommand
import dev.visiontest.cli.commands.ScreenshotCommand
import dev.visiontest.cli.commands.StartAutomationServerCommand
import dev.visiontest.cli.commands.StopAutomationServerCommand
import dev.visiontest.cli.commands.SwipeCommand
import dev.visiontest.cli.commands.SwipeDirectionCommand
import dev.visiontest.cli.commands.SwipeOnElementCommand
import dev.visiontest.cli.commands.TapByCoordinatesCommand
import dev.visiontest.cli.commands.TapOnElementCommand
import dev.visiontest.cli.commands.WaitForElementCommand
import dev.visiontest.config.VersionInfo
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.core.context
import dev.visiontest.performance.TraceRuntime
import dev.visiontest.performance.TraceRecorder
import dev.visiontest.performance.resolveTracePath

/**
 * Root command for the `visiontest` CLI. Dispatches to per-operation subcommands.
 *
 * The JAR's `main(args)` enters this command only when invoked with arguments that
 * are not the MCP stdio sentinel (empty args or `serve`). See [dev.visiontest.main].
 *
 * A [ComponentHolder] is created lazily on first subcommand execution so that
 * `visiontest --help` does not initialize ADB connections or register shutdown hooks.
 */
class VisionTestCli internal constructor(
    private val trace: TraceRuntime,
    componentsProvider: (() -> ComponentHolder)? = null,
    private val resultRunner: (suspend () -> CliResult) -> Unit = { runCliResultCommand(it, trace::finish) },
    readStdin: () -> String = { System.`in`.bufferedReader().readText() },
    acquireComponents: ((TraceRuntime) -> ComponentHolder)? = null,
) : NoOpCliktCommand(name = "visiontest") {
    constructor() : this(TraceRuntime("cli", System.nanoTime()))

    @Suppress("UnusedPrivateProperty") // Eager conversion configures tracing even when help exits parsing.
    private val tracePath by option("--trace-performance", help = "Append host performance spans to a local JSONL file",
        eager = true).convert { value ->
        val path = try { resolveTracePath("cli", value, null) } catch (_: IllegalArgumentException) {
            fail("--trace-performance must specify a nonblank local path")
        }
        trace.configure(path)
        value
    }
    private val components by lazy {
        trace.preparationComplete()
        acquireComponents?.invoke(trace) ?: trace.initializeComponents {
            componentsProvider?.invoke() ?: ComponentHolder.createDefault(TraceRecorder.Contextual)
        }
    }

    private fun runner(name: String): CliCommandRunner = { block ->
        resultRunner { trace.cliInvocation(name) { CliResult(ExitCode.Success.value, block(), null) } }
    }

    init {
        context {
            obj = trace
            argumentFileReader = { java.nio.file.Files.readString(CliExecutionContext.current().resolve(it)) }
            envvarReader = { CliExecutionContext.current().environmentValue(it) }
        }
        versionOption(VersionInfo.version)
        subcommands(
            FlowCommand(lazy { components }, runner = { block ->
                resultRunner { trace.cliInvocation("flow", block) }
            }, readStdin = readStdin, recordingFactory = lazy {
                // FlowExecutor evaluates this inside the original overall/startup budgets,
                // after complete preflight and before any recording subprocess starts.
                if (acquireComponents != null) components
                SystemFlowRecordingFactory()
            }),
            // Setup
            InstallAutomationServerCommand(lazy { components }, runner = runner("install_automation_server")),
            StartAutomationServerCommand(lazy { components }, runner = runner("start_automation_server")),
            StopAutomationServerCommand(lazy { components }, runner = runner("stop_automation_server")),
            AutomationServerStatusCommand(lazy { components }, runner = runner("automation_server_status")),
            // Inspection
            GetInteractiveElementsCommand(lazy { components }, runner = runner("get_interactive_elements")),
            GetUiHierarchyCommand(lazy { components }, runner = runner("get_ui_hierarchy")),
            GetDeviceInfoCommand(lazy { components }, runner = runner("get_device_info")),
            ScreenshotCommand(lazy { components }, runner = runner("screenshot")),
            WaitForElementCommand(lazy { components }, runner = runner("wait_for_element")),
            FindElementCommand(lazy { components }, runner = runner("find_element")),
            AvailableDeviceCommand(lazy { components }, runner = runner("available_device")),
            // Interaction
            TapByCoordinatesCommand(lazy { components }, runner = runner("tap_by_coordinates")),
            TapOnElementCommand(lazy { components }, runner = runner("tap_on_element")),
            InputTextCommand(lazy { components }, runner = runner("input_text")),
            PressKeyCommand(lazy { components }, runner = runner("press_key")),
            ClearTextCommand(lazy { components }, runner = runner("clear_text")),
            LongPressCommand(lazy { components }, runner = runner("long_press")),
            DoubleTapCommand(lazy { components }, runner = runner("double_tap")),
            DismissKeyboardCommand(lazy { components }, runner = runner("dismiss_keyboard")),
            HandleAlertCommand(lazy { components }, runner = runner("handle_alert")),
            SwipeDirectionCommand(lazy { components }, runner = runner("swipe_direction")),
            SwipeCommand(lazy { components }, runner = runner("swipe")),
            SwipeOnElementCommand(lazy { components }, runner = runner("swipe_on_element")),
            // Navigation
            PressBackCommand(lazy { components }, runner = runner("press_back")),
            PressHomeCommand(lazy { components }, runner = runner("press_home")),
            // Apps
            LaunchAppCommand(lazy { components }, runner = runner("launch_app")),
            ListAppsCommand(lazy { components }, runner = runner("list_apps")),
            InfoAppCommand(lazy { components }, runner = runner("info_app")),
            // Project setup
            InitCommand(runner = runner("init"), prepared = trace::preparationComplete),
        )
    }
}
