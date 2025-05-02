package com.example.visiontest

import com.example.visiontest.config.AppConfig
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.util.logging.Logger


fun main()  {

    val config = AppConfig.createDefault()

    val logger = Logger.getLogger("VisionTest")
    logger.info("Starting Vision Test server")

    val android = Android(
        timeoutMillis = config.adbTimeoutMillis,
        cacheValidityPeriod = config.deviceCacheValidityPeriod,
        logger = org.slf4j.LoggerFactory.getLogger(Android::class.java)
    )

    val server = createServer(config)

    val toolFactory = ToolFactory(android, logger)
    toolFactory.registerAllTools(server)

    // Connect using stdio transport
    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asInput(),
        System.out.asSink().buffered()
    )

    runBlocking {
        logger.info("Connecting server")
        server.connect(transport)
        val done = Job()
        server.onClose {
            logger.info("Server closing")
            done.complete()
        }
        done.join()
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