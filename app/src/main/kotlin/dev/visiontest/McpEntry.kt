package dev.visiontest

import dev.visiontest.performance.TraceRuntime
import dev.visiontest.performance.resolveTracePath
import dev.visiontest.android.Android
import dev.visiontest.ios.IOSManager
import dev.visiontest.config.AppConfig
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import dev.visiontest.common.RequestLogger

internal fun runMcpServer(trace: TraceRuntime) {
    trace.configure(resolveTracePath("mcp", null, System.getenv("VISIONTEST_TRACE_PERFORMANCE")))
    Runtime.getRuntime().addShutdownHook(Thread { trace.finish() })

    val config = AppConfig.createDefault()

    val logger = RequestLogger.getLogger("VisionTest")
    logger.info("Starting Vision Test server")

    val android = Android(
        timeoutMillis = config.adbTimeoutMillis,
        cacheValidityPeriod = config.deviceCacheValidityPeriod,
        logger = RequestLogger.getLogger(Android::class.java),
        trace = trace.recorder
    )

    val ios = IOSManager(
        logger = RequestLogger.getLogger(IOSManager::class.java),
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
