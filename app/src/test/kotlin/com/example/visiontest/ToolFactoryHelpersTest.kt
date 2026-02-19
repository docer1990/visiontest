package com.example.visiontest

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.MobileDevice
import com.example.visiontest.ios.IOSAutomationClient
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolFactoryHelpersTest {

    // Minimal DeviceConfig stub - methods won't be called in helper tests
    private val stubDevice = object : DeviceConfig {
        override suspend fun listDevices(): List<MobileDevice> = emptyList()
        override suspend fun getFirstAvailableDevice(): MobileDevice = throw NotImplementedError()
        override suspend fun listApps(deviceId: String?): List<String> = emptyList()
        override suspend fun getAppInfo(packageName: String, deviceId: String?): String = ""
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?): Boolean = false
        override suspend fun executeShell(command: String, deviceId: String?): String = ""
    }

    private val factory = ToolFactory(
        android = stubDevice,
        ios = stubDevice,
        logger = LoggerFactory.getLogger(ToolFactoryHelpersTest::class.java),
        automationClient = AutomationClient(),
        iosAutomationClient = IOSAutomationClient()
    )

    // --- extractProperty ---

    @Test
    fun `extractProperty finds property value`() {
        val output = "[ro.product.model]: [Pixel 6]\n[ro.build.version.release]: [14]"
        assertEquals("Pixel 6", factory.extractProperty(output, "ro.product.model"))
    }

    @Test
    fun `extractProperty returns Unknown when property missing`() {
        val output = "[ro.product.model]: [Pixel 6]"
        assertEquals("Unknown", factory.extractProperty(output, "ro.build.version.sdk"))
    }

    @Test
    fun `extractProperty handles empty output`() {
        assertEquals("Unknown", factory.extractProperty("", "ro.product.model"))
    }

    @Test
    fun `extractProperty finds correct value among multiple properties`() {
        val output = """
            [ro.product.model]: [Pixel 6]
            [ro.build.version.release]: [14]
            [ro.build.version.sdk]: [34]
        """.trimIndent()
        assertEquals("14", factory.extractProperty(output, "ro.build.version.release"))
        assertEquals("34", factory.extractProperty(output, "ro.build.version.sdk"))
    }

    // --- extractPattern ---

    @Test
    fun `extractPattern returns matched group`() {
        assertEquals("1.0.0", factory.extractPattern("versionName=1.0.0", "versionName=(\\S+)"))
    }

    @Test
    fun `extractPattern returns Unknown on no match`() {
        assertEquals("Unknown", factory.extractPattern("no match here", "versionName=(\\S+)"))
    }

    // --- formatAppInfo ---

    @Test
    fun `formatAppInfo extracts version information`() {
        val rawInfo = """
            versionName=2.1.0
            versionCode=42
            targetSdk=34
            minSdk=21
            firstInstallTime=2024-01-15
            lastUpdateTime=2024-06-20
        """.trimIndent()

        val result = factory.formatAppInfo(rawInfo, "com.example.app")
        assertTrue(result.contains("2.1.0"))
        assertTrue(result.contains("42"))
        assertTrue(result.contains("34"))
        assertTrue(result.contains("21"))
        assertTrue(result.contains("2024-01-15"))
        assertTrue(result.contains("2024-06-20"))
        assertTrue(result.contains("com.example.app"))
    }

    @Test
    fun `formatAppInfo returns Unknown for missing fields`() {
        val result = factory.formatAppInfo("", "com.test")
        assertTrue(result.contains("Unknown"))
    }

    @Test
    fun `formatAppInfo limits permissions to first 10`() {
        val permissions = (1..15).joinToString("\n") { "    android.permission.PERM_$it" }
        val rawInfo = """
            versionName=1.0
            versionCode=1
            grantedPermissions:
            $permissions

            otherSection:
        """.trimIndent()

        val result = factory.formatAppInfo(rawInfo, "com.test")
        // Should contain at most 10 permission entries
        val permCount = Regex("android\\.permission\\.PERM_").findAll(result).count()
        assertTrue(permCount <= 10, "Expected at most 10 permissions, got $permCount")
    }
}
