package dev.visiontest

import dev.visiontest.daemon.CliRoute
import dev.visiontest.daemon.classifyCliArguments
import dev.visiontest.daemon.runDaemonClient
import dev.visiontest.daemon.runDaemonHost
import dev.visiontest.performance.TraceRuntime

fun main(args: Array<String>) {
    val entryNs = System.nanoTime()
    val routing = classifyCliArguments(args)
    when (routing.route) {
        CliRoute.MCP -> runMcpServer(TraceRuntime("mcp", entryNs))
        CliRoute.HOST -> runDaemonHost(routing.arguments)
        CliRoute.LOCAL, CliRoute.STANDALONE -> runLocalCli(routing.arguments.toTypedArray(), entryNs)
        CliRoute.LIFECYCLE, CliRoute.DAEMON -> kotlin.system.exitProcess(
            runDaemonClient(routing.arguments, routing.route == CliRoute.LIFECYCLE))
    }
}
