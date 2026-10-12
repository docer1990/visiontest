package com.example.visiontest

import com.example.visiontest.performance.TraceRuntime
import com.example.visiontest.performance.resolveTracePath
import com.example.visiontest.android.Android
import com.example.visiontest.cli.CliExit
import com.example.visiontest.cli.ExitCode
import com.example.visiontest.cli.VisionTestCli
import com.example.visiontest.ios.IOSManager
import com.example.visiontest.config.AppConfig
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import org.slf4j.LoggerFactory


fun main(args: Array<String>) {
    val entryNs = System.nanoTime()
    when (route(args)) {
        Route.McpServer -> runMcpServer(TraceRuntime("mcp", entryNs))
        Route.Cli -> runCli(args, TraceRuntime("cli", entryNs))
    }
}

private fun runCli(args: Array<String>, trace: TraceRuntime) {
    try {
        VisionTestCli(trace).parse(args)
    } catch (e: CliExit) {
        // Safety net: all CliExit exceptions should be caught by runCliCommand inside
        // each subcommand's run(). This catch handles any that escape during arg parsing.
        System.err.println(e.message)
        finishCli(trace, "other", e)
        kotlin.system.exitProcess(e.code.value)
    } catch (e: UsageError) {
        val defaultFormatter = object : com.github.ajalt.clikt.output.ParameterFormatter {
            override fun formatOption(name: String) = name
            override fun formatArgument(name: String) = "<$name>"
            override fun formatSubcommand(name: String) = name
        }
        val loc = e.context?.localization ?: object : com.github.ajalt.clikt.output.Localization {}
        val msg = e.formatMessage(loc, defaultFormatter)
        System.err.println(msg)
        finishCli(trace, e.context?.command?.commandName ?: "other", e)
        kotlin.system.exitProcess(ExitCode.UsageError.value)
    } catch (e: PrintHelpMessage) {
        val cmd = e.context?.command
        if (cmd != null) {
            println(cmd.getFormattedHelp())
        }
        finishCli(trace, "help", if (e.error) e else null)
        kotlin.system.exitProcess(if (e.error) 1 else 0)
    } catch (e: PrintMessage) {
        // Informational output such as --version: print to stdout, exit per the message.
        e.message?.let { println(it) }
        finishCli(trace, "version", null)
        kotlin.system.exitProcess(e.statusCode)
    } catch (e: CliktError) {
        System.err.println(e.message.orEmpty())
        finishCli(trace, "other", e)
        kotlin.system.exitProcess(ExitCode.GenericFailure.value)
    }
    trace.finish()
}

private fun finishCli(trace: TraceRuntime, operation: String, failure: Throwable?) {
    runBlocking {
        runCatching { trace.cliInvocation(operation) { if (failure != null) throw failure } }
    }
    trace.finish()
}

internal enum class Route { McpServer, Cli }

internal fun route(args: Array<String>): Route =
    if (args.isEmpty() || args[0] == "serve") Route.McpServer else Route.Cli

private fun runMcpServer(trace: TraceRuntime) {
    trace.configure(resolveTracePath("mcp", null, System.getenv("VISIONTEST_TRACE_PERFORMANCE")))
    Runtime.getRuntime().addShutdownHook(Thread { trace.finish() })

    val config = AppConfig.createDefault()

    val logger = LoggerFactory.getLogger("VisionTest")
    logger.info("Starting Vision Test server")

    val android = Android(
        timeoutMillis = config.adbTimeoutMillis,
        cacheValidityPeriod = config.deviceCacheValidityPeriod,
        logger = LoggerFactory.getLogger(Android::class.java),
        trace = trace.recorder
    )

    val ios = IOSManager(
        logger = LoggerFactory.getLogger(IOSManager::class.java),
        trace = trace.recorder
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down server")
        android.close()
        ios.close()
        logger.info("Server shut down complete")
    })

    val server = createServer(config)

    val toolFactory = ToolFactory(
        android, ios, logger, recorder = trace.recorder, toolTimeoutMillis = config.toolTimeoutMillis
    )
    toolFactory.registerAllTools(server, trace.recorder)

    // Connect using stdio transport
    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asInput(),
        System.out.asSink().buffered()
    )

    runBlocking {
        try {
            logger.info("Connecting server")
            server.connect(transport)
            val done = Job()
            server.onClose {
                logger.info("Server connection closed")
                done.complete()
            }
            done.join()
        } finally {
            trace.finish()
            android.close()
            ios.close()
        }
    }
}

/**
 * Creates and configures the MCP server.
 *
 * @param config Application configuration
 * @return Configured MCP server
 */
private fun createServer(config: AppConfig): Server {
    return Server(
        Implementation(
            name = config.serverName,
            version = config.serverVersion,
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )
}
