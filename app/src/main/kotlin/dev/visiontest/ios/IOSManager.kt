package dev.visiontest.ios

import dev.visiontest.performance.TraceRecorder
import dev.visiontest.common.DeviceConfig
import dev.visiontest.common.MobileDevice
import org.slf4j.Logger
import dev.visiontest.common.RequestLogger

class IOSManager internal constructor(
    private val logger: Logger = RequestLogger.getLogger(IOSManager::class.java),
    private val trace: TraceRecorder,
) : DeviceConfig, AutoCloseable {
    constructor(logger: Logger = RequestLogger.getLogger(IOSManager::class.java)) :
        this(logger, TraceRecorder.Disabled)
    constructor() : this(trace = TraceRecorder.Disabled)

    private val simulator: DeviceConfig = IOSSimulator(trace = trace)

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
