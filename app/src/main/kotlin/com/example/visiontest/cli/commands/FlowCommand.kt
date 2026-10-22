package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CompactInspectionCliOptions
import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.CliResult
import com.example.visiontest.cli.FlowExecutor
import com.example.visiontest.cli.FlowRecordingFactory
import com.example.visiontest.cli.FlowRecordingOptions
import com.example.visiontest.cli.SystemFlowRecordingFactory
import com.example.visiontest.cli.FlowObservationOptions
import com.example.visiontest.cli.FlowObservationCollector
import com.example.visiontest.cli.IosAppScopeOptions
import com.example.visiontest.cli.FlowInput
import com.example.visiontest.cli.FlowPreparation
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliResultCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import java.nio.file.Path
import java.util.UUID

internal class FlowCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: (suspend () -> CliResult) -> Unit = { runCliResultCommand(it) },
    private val readStdin: () -> String = { System.`in`.bufferedReader().readText() },
    private val recordingFactory: Lazy<FlowRecordingFactory> = lazy { SystemFlowRecordingFactory() },
) : CliktCommand(name = "flow", help = """
    Execute UI commands sequentially through one device session. Stops on the first failure.
    Completed actions remain applied. No rollback, replay, or automatic retry occurs.

    Supply repeated --step, --file, or --stdin. Each line is one command. Single and double
    quotes group arguments, including empty arguments. Backslash escapes outside single quotes.
    Blank lines and lines whose first non-whitespace character is # are ignored. No inline
    comments, shell execution, variable expansion, or command expansion occurs. Other # characters are literal.

    Both platforms support tap_by_coordinates, tap_on_element, input_text, long_press,
    double_tap, swipe, swipe_direction, swipe_on_element, press_home, wait_for_element,
    and screenshot. Android also supports press_back, press_key, clear_text. iOS also
    supports dismiss_keyboard, handle_alert. Step arguments match individual commands.

    Set --platform only on flow. Final observations run after successful steps and use the
    remaining overall budget. No observations are collected unless requested. The outer
    --bundle-id scopes only iOS hierarchy and interactive-element observations and evidence;
    each step keeps its own scope. Omitted iOS observation scope targets Springboard.
    Screenshots always capture the full display. Observation artifacts use flow-artifacts/<invocation>/.

    The earliest operation, step, or overall deadline wins. Overall time includes component
    creation and final observations; parsing is excluded. Failure evidence adds a separate
    total budget after an action or final-observation failure and preserves its exit code.
""".trimIndent()) {
    private val platform by platformOption()
    private val appScope by IosAppScopeOptions()
    private val compactInspection by CompactInspectionCliOptions()
    private val observe by option("--observe",
        help = "Final observation: screenshot, hierarchy, interactive-elements").multiple()
    private val onFailure by option("--on-failure",
        help = "Evidence kind: screenshot, hierarchy, interactive-elements").multiple()
    private val evidenceTimeout by option("--evidence-timeout",
        help = "Additional total evidence budget, default 5000 ms; 1..30000")
        .int().default(DEFAULT_EVIDENCE_TIMEOUT_MS)
    private val steps by option("--step", help = "One command line; repeat to append steps").multiple()
    private val file by option("--file", help = "Read commands from a UTF-8 file")
    private val stdin by option("--stdin", help = "Read commands from standard input").flag()
    private val json by option("--json", help = "Print a complete JSON execution report").flag()
    private val record by option("--record", help = "Require a saved screen recording of the flow").flag()
    private val stepTimeout by option("--step-timeout", help = "Per-step budget: default 35000 ms; range 1..3600000")
        .int().default(DEFAULT_STEP_TIMEOUT_MS)
    private val timeout by option("--timeout", help = "Overall execution budget: default 120000 ms; range 1..3600000")
        .int().default(DEFAULT_TIMEOUT_MS)

    private companion object {
        const val DEFAULT_STEP_TIMEOUT_MS = 35000
        const val DEFAULT_TIMEOUT_MS = 120000
        const val MAX_TIMEOUT_MS = 3600000
        const val DEFAULT_EVIDENCE_TIMEOUT_MS = 5000
        const val MAX_EVIDENCE_TIMEOUT_MS = 30000
    }

    override fun run() = runner {
        require(stepTimeout in 1..MAX_TIMEOUT_MS) { "--step-timeout must be between 1 and 3600000" }
        require(timeout in 1..MAX_TIMEOUT_MS) { "--timeout must be between 1 and 3600000" }
        appScope.validate(platform)
        require(evidenceTimeout in 1..MAX_EVIDENCE_TIMEOUT_MS) { "--evidence-timeout must be between 1 and 30000" }
        require((onFailure + observe).all { it in FlowObservationCollector.kinds }) {
            "Observation kinds must be screenshot, hierarchy, or interactive-elements"
        }
        val inspectionOptions = compactInspection.options()
        inspectionOptions.validate()
        require(!inspectionOptions.compact || "interactive-elements" in (onFailure + observe)) {
            "Compact flow options require an interactive-elements observation or failure evidence request"
        }
        val lines = FlowInput.read(steps, file, stdin, readStdin)
        val prepared = FlowPreparation.prepare(platform, lines, components)
        val artifacts = Path.of("flow-artifacts", UUID.randomUUID().toString()).toAbsolutePath()
        val report = FlowExecutor().execute(platform, prepared, stepTimeout.toLong(), timeout.toLong(),
            FlowObservationOptions(FlowObservationCollector(platform, components, appScope.bundleId,
                artifacts = artifacts, options = inspectionOptions),
                onFailure, evidenceTimeout.toLong(), observe),
            recordingOptions = if (record) FlowRecordingOptions(artifacts, recordingFactory) else null)
        CliResult(report.exitCode, if (json) report.toJson().toString() else report.text(), report.error?.message)
    }
}
