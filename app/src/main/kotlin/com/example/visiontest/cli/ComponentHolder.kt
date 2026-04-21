package com.example.visiontest.cli

import com.example.visiontest.android.Android
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.config.AppConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSManager
import com.example.visiontest.tools.AndroidAutomationToolRegistrar
import com.example.visiontest.tools.AndroidDeviceToolRegistrar
import com.example.visiontest.tools.IOSAutomationToolRegistrar
import com.example.visiontest.tools.IOSDeviceToolRegistrar
import org.slf4j.LoggerFactory

/**
 * Minimal object graph for the CLI path.
 *
 * Mirrors the wiring in [com.example.visiontest.main] / [com.example.visiontest.ToolFactory]
 * so that CLI subcommands use the identical set of dependencies that the MCP tools use.
 *
 * The holder is created once per CLI invocation (in [VisionTestCli]) and registers
 * the same shutdown-hook behavior (close [android] and [ios] on JVM exit).
 */
class ComponentHolder private constructor(val config: AppConfig) {

    private val logger = LoggerFactory.getLogger("VisionTest")

    val android: Android = Android(
        timeoutMillis = config.adbTimeoutMillis,
        cacheValidityPeriod = config.deviceCacheValidityPeriod,
        logger = LoggerFactory.getLogger(Android::class.java)
    )

    val ios: IOSManager = IOSManager(
        logger = LoggerFactory.getLogger(IOSManager::class.java)
    )

    val automationClient: AutomationClient = AutomationClient()
    val iosAutomationClient: IOSAutomationClient = IOSAutomationClient()

    private val discovery = ToolDiscovery(logger)

    val androidDeviceRegistrar = AndroidDeviceToolRegistrar(android)
    val androidAutomationRegistrar = AndroidAutomationToolRegistrar(android, automationClient, discovery)
    val iosDeviceRegistrar = IOSDeviceToolRegistrar(ios)
    val iosAutomationRegistrar = IOSAutomationToolRegistrar(ios, iosAutomationClient, discovery, logger)

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down CLI")
            android.close()
            ios.close()
            logger.info("CLI shutdown complete")
        })
    }

    companion object {
        /**
         * Creates a [ComponentHolder] using [AppConfig.createDefault].
         */
        fun createDefault(): ComponentHolder = ComponentHolder(AppConfig.createDefault())
    }
}
