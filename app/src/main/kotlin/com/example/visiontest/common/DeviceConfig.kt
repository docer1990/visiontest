package com.example.visiontest.common

interface DeviceConfig {
    suspend fun listDevices(): List<MobileDevice>
    suspend fun getFirstAvailableDevice(): MobileDevice
    suspend fun listApps(deviceId: String? = null): List<String>
    suspend fun getAppInfo(packageName: String, deviceId: String? = null): String
    suspend fun launchApp(packageName: String, activityName: String? = null, deviceId: String? = null): Boolean
    suspend fun executeShell(command: String, deviceId: String? = null): String
}