package dev.visiontest

import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.cli.CliRequestExecutor

internal fun runLocalCli(args: Array<String>, entryNs: Long) {
    val result = CliRequestExecutor { entryNs }.execute(args, CliExecutionContext.local())
    result.stdout?.let(::println)
    result.stderr?.let(System.err::println)
    kotlin.system.exitProcess(result.exitCode)
}
