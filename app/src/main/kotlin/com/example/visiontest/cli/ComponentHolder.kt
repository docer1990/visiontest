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
class ComponentHolder internal constructor(
    val android: Android,
    val ios: IOSManager,
    val automationClient: AutomationClient,
    val iosAutomationClient: IOSAutomationClient,
    val androidDeviceRegistrar: AndroidDeviceToolRegistrar,
    val androidAutomationRegistrar: AndroidAutomationToolRegistrar,
    val iosDeviceRegistrar: IOSDeviceToolRegistrar,
    val iosAutomationRegistrar: IOSAutomationToolRegistrar,
) {

    /** Returns `true` if the automation server for the given platform is reachable. */
    suspend fun isServerRunning(platform: Platform): Boolean = when (platform) {
        Platform.Android -> automationClient.isServerRunning()
        Platform.Ios -> iosAutomationClient.isServerRunning()
    }

    companion object {
        /**
         * Creates a [ComponentHolder] using [AppConfig.createDefault] with the standard
         * production wiring. Registers a shutdown hook to close device connections.
         */
        fun createDefault(): ComponentHolder {
            val config = AppConfig.createDefault()
            val logger = LoggerFactory.getLogger("VisionTest")

            val android = Android(
                timeoutMillis = config.adbTimeoutMillis,
                cacheValidityPeriod = config.deviceCacheValidityPeriod,
                logger = LoggerFactory.getLogger(Android::class.java)
            )

            val ios = IOSManager(
                logger = LoggerFactory.getLogger(IOSManager::class.java)
            )

            val automationClient = AutomationClient()
            val iosAutomationClient = IOSAutomationClient()
            val discovery = ToolDiscovery(logger)

            Runtime.getRuntime().addShutdownHook(Thread {
                logger.info("Shutting down CLI")
                android.close()
                ios.close()
                logger.info("CLI shutdown complete")
            })

            return ComponentHolder(
                android = android,
                ios = ios,
                automationClient = automationClient,
                iosAutomationClient = iosAutomationClient,
                androidDeviceRegistrar = AndroidDeviceToolRegistrar(android),
                androidAutomationRegistrar = AndroidAutomationToolRegistrar(android, automationClient, discovery),
                iosDeviceRegistrar = IOSDeviceToolRegistrar(ios),
                iosAutomationRegistrar = IOSAutomationToolRegistrar(ios, iosAutomationClient, discovery, logger),
            )
        }
    }
}
