package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for the extracted `internal suspend` functions on [AndroidDeviceToolRegistrar],
 * exercised directly without going through ToolScope/MCP.
 */
class AndroidDeviceToolRegistrarTest {

    private val fakeDevice = MobileDevice(
        id = "emulator-5554",
        name = "Pixel_6",
        type = DeviceType.ANDROID,
        state = "device"
    )

    private val fakeDeviceConfig = object : DeviceConfig {
        var apps: List<String> = listOf("com.example.app1", "com.example.app2")
        var appInfo: String = "versionName=1.0.0\nversionCode=1"
        var launchResult: Boolean = true
        var shellOutput: String = "[ro.product.model]: [Pixel 6]\n[ro.build.version.release]: [13]\n[ro.build.version.sdk]: [33]"

        override suspend fun listDevices() = listOf(fakeDevice)
        override suspend fun getFirstAvailableDevice() = fakeDevice
        override suspend fun listApps(deviceId: String?) = apps
        override suspend fun getAppInfo(packageName: String, deviceId: String?) = appInfo
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?) = launchResult
        override suspend fun executeShell(command: String, deviceId: String?) = shellOutput
    }

    private val registrar = AndroidDeviceToolRegistrar(fakeDeviceConfig)

    @Test
    fun `availableDevice returns formatted device info`() = runBlocking {
        val result = registrar.availableDevice()
        assertTrue(result.contains("Serial: emulator-5554"))
        assertTrue(result.contains("State: device"))
    }

    @Test
    fun `listApps returns formatted app list`() = runBlocking {
        val result = registrar.listApps()
        assertTrue(result.contains("com.example.app1"))
        assertTrue(result.contains("com.example.app2"))
    }

    @Test
    fun `listApps handles empty list`() = runBlocking {
        fakeDeviceConfig.apps = emptyList()
        val result = registrar.listApps()
        assertEquals("No apps found on the device", result)
    }

    @Test
    fun `infoApp delegates to DeviceConfig`() = runBlocking {
        val result = registrar.infoApp("com.example.app1")
        // ToolHelpers.formatAppInfo is tested elsewhere; just verify delegation
        assertFalse(result.isEmpty())
    }

    @Test
    fun `launchApp success`() = runBlocking {
        val result = registrar.launchApp("com.example.app1")
        assertEquals("Successfully launched the app: com.example.app1", result)
    }

    @Test
    fun `launchApp failure`() = runBlocking {
        fakeDeviceConfig.launchResult = false
        val result = registrar.launchApp("com.example.app1")
        assertEquals("Failed to launch the app: com.example.app1", result)
    }
}
