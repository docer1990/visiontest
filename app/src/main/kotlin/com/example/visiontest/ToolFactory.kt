package com.example.visiontest

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.tools.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.slf4j.Logger

class ToolFactory(
    private val android: DeviceConfig,
    private val ios: DeviceConfig,
    private val logger: Logger,
    private val toolTimeoutMillis: Long = 10000L,
    private val automationClient: AutomationClient = AutomationClient(),
    private val iosAutomationClient: IOSAutomationClient = IOSAutomationClient()
) {

    private val discovery = ToolDiscovery(logger)

    private val registrars: List<ToolRegistrar> = listOf(
        AndroidDeviceToolRegistrar(android),
        AndroidAutomationToolRegistrar(android, automationClient, discovery),
        IOSDeviceToolRegistrar(ios),
        IOSAutomationToolRegistrar(ios, iosAutomationClient, discovery, logger)
    )

    fun registerAllTools(server: Server) {
        val scope = ToolScope(server, logger, toolTimeoutMillis)
        registrars.forEach { it.registerTools(scope) }
    }
}
