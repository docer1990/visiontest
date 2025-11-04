package com.example.visiontest.ios

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.MobileDevice
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class IOSManager(
    private val logger: Logger = LoggerFactory.getLogger(IOSManager::class.java)
) : DeviceConfig, AutoCloseable {

    private val simulator: DeviceConfig = IOSSimulator()

    override suspend fun listDevices(): List<MobileDevice> {
        return simulator.listDevices()
    }

    override suspend fun getFirstAvailableDevice(): MobileDevice {
        return simulator.getFirstAvailableDevice()
    }

    override suspend fun listApps(deviceId: String?): List<String> {
        return simulator.listApps(deviceId)
    }

    override suspend fun getAppInfo(packageName: String, deviceId: String?): String {
        return simulator.getAppInfo(packageName, deviceId)
    }

    override suspend fun launchApp(
        packageName: String,
        activityName: String?,
        deviceId: String?
    ): Boolean {
        return simulator.launchApp(packageName, activityName, deviceId)
    }

    override suspend fun executeShell(command: String, deviceId: String?): String {
        return simulator.executeShell(command, deviceId)
    }

    override fun close() {
        logger.info("Closing IOSManager resources")
    }
}