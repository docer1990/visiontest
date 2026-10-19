package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.CliResult
import com.example.visiontest.cli.FlowExecutor
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

internal class FlowCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: (suspend () -> CliResult) -> Unit = { runCliResultCommand(it) },
    private val readStdin: () -> String = { System.`in`.bufferedReader().readText() },
) : CliktCommand(name = "flow", help = "Execute a validated sequence of UI commands") {
    private val platform by platformOption()
    private val appScope by IosAppScopeOptions()
    private val observe by option("--observe", help = "Final observation: screenshot, hierarchy, interactive-elements").multiple()
    private val onFailure by option("--on-failure", help = "Evidence kind: screenshot, hierarchy, interactive-elements").multiple()
    private val evidenceTimeout by option("--evidence-timeout", help = "Additional total evidence budget, default 5000 ms; 1..30000")
        .int().default(5000)
    private val steps by option("--step", help = "One command line; repeat to append steps").multiple()
    private val file by option("--file", help = "Read commands from a UTF-8 file")
    private val stdin by option("--stdin", help = "Read commands from standard input").flag()
    private val json by option("--json", help = "Print a complete JSON execution report").flag()
    private val stepTimeout by option("--step-timeout", help = "Per-step budget in milliseconds")
        .int().default(DEFAULT_STEP_TIMEOUT_MS)
    private val timeout by option("--timeout", help = "Overall execution budget in milliseconds")
        .int().default(DEFAULT_TIMEOUT_MS)

    private companion object {
        const val DEFAULT_STEP_TIMEOUT_MS = 35000
        const val DEFAULT_TIMEOUT_MS = 120000
        const val MAX_TIMEOUT_MS = 3600000
    }

    override fun run() = runner {
        require(stepTimeout in 1..MAX_TIMEOUT_MS) { "--step-timeout must be between 1 and 3600000" }
        require(timeout in 1..MAX_TIMEOUT_MS) { "--timeout must be between 1 and 3600000" }
        appScope.validate(platform)
        require(evidenceTimeout in 1..30000) { "--evidence-timeout must be between 1 and 30000" }
        require((onFailure + observe).all { it in FlowObservationCollector.kinds }) { "Invalid failure evidence kind" }
        val lines = FlowInput.read(steps, file, stdin, readStdin)
        val prepared = FlowPreparation.prepare(platform, lines, components)
        val report = FlowExecutor().execute(platform, prepared, stepTimeout.toLong(), timeout.toLong(),
            FlowObservationCollector(platform, components, appScope.bundleId), onFailure, evidenceTimeout.toLong(), observe)
        CliResult(report.exitCode, if (json) report.toJson().toString() else report.text(), report.error?.message)
    }
}
