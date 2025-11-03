package com.example.visiontest

import com.example.visiontest.config.AppConfig
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import org.slf4j.LoggerFactory


fun main()  {

    val config = AppConfig.createDefault()

    val logger = LoggerFactory.getLogger("VisionTest")
    logger.info("Starting Vision Test server")

    val android = Android(
        timeoutMillis = config.adbTimeoutMillis,
        cacheValidityPeriod = config.deviceCacheValidityPeriod,
        logger = LoggerFactory.getLogger(Android::class.java)
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down server")
        android.close()
        logger.info("Server shut down complete")
    })

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
            android.close()
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