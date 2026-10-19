package com.example.visiontest.cli.commands

import com.example.visiontest.cli.ComponentHolder
import com.example.visiontest.cli.CliResult
import com.example.visiontest.cli.FlowExecutor
import com.example.visiontest.cli.FlowInput
import com.example.visiontest.cli.FlowPreparation
import com.example.visiontest.cli.platformOption
import com.example.visiontest.cli.runCliResultCommand
import com.github.ajalt.clikt.core.CliktCommand
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
    private val steps by option("--step", help = "One command line; repeat to append steps").multiple()
    private val file by option("--file", help = "Read commands from a UTF-8 file")
    private val stdin by option("--stdin", help = "Read commands from standard input").flag()
    private val json by option("--json", help = "Print a complete JSON execution report").flag()
    private val stepTimeout by option("--step-timeout", help = "Per-step budget in milliseconds").int().default(35000)
    private val timeout by option("--timeout", help = "Overall execution budget in milliseconds").int().default(120000)

    override fun run() = runner {
        require(stepTimeout in 1..3600000) { "--step-timeout must be between 1 and 3600000" }
        require(timeout in 1..3600000) { "--timeout must be between 1 and 3600000" }
        val lines = FlowInput.read(steps, file, stdin, readStdin)
        val prepared = FlowPreparation.prepare(platform, lines, components)
        val report = FlowExecutor().execute(platform, prepared)
        CliResult(report.exitCode, if (json) report.toJson().toString() else report.text(), report.error?.message)
    }
}
