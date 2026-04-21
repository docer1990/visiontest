package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for the extracted `internal suspend` functions on [IOSDeviceToolRegistrar],
 * exercised directly without going through ToolScope/MCP.
 */
class IOSDeviceToolRegistrarTest {

    private val fakeDevice = MobileDevice(
        id = "ABCD-1234",
        name = "iPhone 16",
        type = DeviceType.IOS_SIMULATOR,
        state = "Booted",
        osVersion = "18.0",
        modelName = "iPhone 16"
    )

    private val fakeDeviceConfig = object : DeviceConfig {
        var apps: List<String> = listOf("com.apple.mobilesafari", "com.apple.Preferences")
        var appInfo: String = "BundleID: com.apple.mobilesafari\nPath: /some/path"
        var launchResult: Boolean = true

        override suspend fun listDevices() = listOf(fakeDevice)
        override suspend fun getFirstAvailableDevice() = fakeDevice
        override suspend fun listApps(deviceId: String?) = apps
        override suspend fun getAppInfo(packageName: String, deviceId: String?) = appInfo
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?) = launchResult
        override suspend fun executeShell(command: String, deviceId: String?) = ""
    }

    private val registrar = IOSDeviceToolRegistrar(fakeDeviceConfig)

    @Test
    fun `availableDevice returns formatted device info`() = runBlocking {
        val result = registrar.availableDevice()
        assertTrue(result.contains("ID: ABCD-1234"))
        assertTrue(result.contains("Name: iPhone 16"))
        assertTrue(result.contains("State: Booted"))
        assertTrue(result.contains("OS Version: 18.0"))
    }

    @Test
    fun `listApps returns formatted app list`() = runBlocking {
        val result = registrar.listApps()
        assertTrue(result.contains("com.apple.mobilesafari"))
    }

    @Test
    fun `listApps handles empty list`() = runBlocking {
        fakeDeviceConfig.apps = emptyList()
        val result = registrar.listApps()
        assertEquals("No apps found on the iOS device", result)
    }

    @Test
    fun `infoApp formats with bundleId`() = runBlocking {
        val result = registrar.infoApp("com.apple.mobilesafari")
        assertTrue(result.startsWith("App Information for com.apple.mobilesafari:"))
    }

    @Test
    fun `launchApp success`() = runBlocking {
        val result = registrar.launchApp("com.apple.mobilesafari")
        assertEquals("Successfully launched the iOS app: com.apple.mobilesafari", result)
    }

    @Test
    fun `launchApp failure`() = runBlocking {
        fakeDeviceConfig.launchResult = false
        val result = registrar.launchApp("com.apple.mobilesafari")
        assertEquals("Failed to launch the iOS app: com.apple.mobilesafari", result)
    }
}
