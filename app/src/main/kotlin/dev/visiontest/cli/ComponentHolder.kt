package dev.visiontest.cli

import dev.visiontest.performance.TraceRecorder
import dev.visiontest.android.Android
import dev.visiontest.android.AutomationClient
import dev.visiontest.config.AppConfig
import dev.visiontest.discovery.ToolDiscovery
import dev.visiontest.ios.IOSAutomationClient
import dev.visiontest.ios.IOSManager
import dev.visiontest.tools.AndroidAutomationToolRegistrar
import dev.visiontest.tools.AndroidDeviceToolRegistrar
import dev.visiontest.tools.AndroidStopToolRegistrar
import dev.visiontest.tools.AndroidWaitToolRegistrar
import dev.visiontest.tools.IOSAutomationToolRegistrar
import dev.visiontest.tools.IOSDeviceToolRegistrar
import dev.visiontest.tools.IOSWaitToolRegistrar
import org.slf4j.LoggerFactory

/**
 * Minimal object graph for the CLI path.
 *
 * Mirrors the wiring in [dev.visiontest.main] / [dev.visiontest.ToolFactory]
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
    private fun configureTracing(recorder: TraceRecorder) {
        if (recorder === TraceRecorder.Disabled) return
        androidStopRegistrar = AndroidStopToolRegistrar(android, automationClient, recorder)
        androidWaitRegistrar = AndroidWaitToolRegistrar(automationClient, recorder)
        iosWaitRegistrar = IOSWaitToolRegistrar(iosAutomationClient, recorder)
    }

    // Derived from the clients above rather than injected: these registrars have no
    // other dependencies, and keeping them out of the constructor preserves its shape.
    var androidStopRegistrar = AndroidStopToolRegistrar(android, automationClient)
        private set
    var androidWaitRegistrar = AndroidWaitToolRegistrar(automationClient)
        private set
    var iosWaitRegistrar = IOSWaitToolRegistrar(iosAutomationClient)
        private set

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
        fun createDefault(): ComponentHolder = createDefault(TraceRecorder.Disabled)

        internal fun createDefault(recorder: TraceRecorder): ComponentHolder {
            val config = AppConfig.createDefault()
            val logger = LoggerFactory.getLogger("VisionTest")

            val android = Android(
                timeoutMillis = config.adbTimeoutMillis,
                cacheValidityPeriod = config.deviceCacheValidityPeriod,
                logger = LoggerFactory.getLogger(Android::class.java),
                trace = recorder
            )

            val ios = IOSManager(
                logger = LoggerFactory.getLogger(IOSManager::class.java),
                trace = recorder
            )

            val automationClient = AutomationClient(trace = recorder)
            val iosAutomationClient = IOSAutomationClient(trace = recorder)
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
                androidDeviceRegistrar = AndroidDeviceToolRegistrar(android, recorder),
                androidAutomationRegistrar =
                    AndroidAutomationToolRegistrar(android, automationClient, discovery, recorder),
                iosDeviceRegistrar = IOSDeviceToolRegistrar(ios, recorder),
                iosAutomationRegistrar =
                    IOSAutomationToolRegistrar(ios, iosAutomationClient, discovery, logger, recorder),
            ).also { it.configureTracing(recorder) }
        }
    }
}
