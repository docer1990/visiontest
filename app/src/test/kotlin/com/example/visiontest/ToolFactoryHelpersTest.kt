package com.example.visiontest

import com.example.visiontest.tools.ToolHelpers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolFactoryHelpersTest {

    // --- extractProperty ---

    @Test
    fun `extractProperty finds property value`() {
        val output = "[ro.product.model]: [Pixel 6]\n[ro.build.version.release]: [14]"
        assertEquals("Pixel 6", ToolHelpers.extractProperty(output, "ro.product.model"))
    }

    @Test
    fun `extractProperty returns Unknown when property missing`() {
        val output = "[ro.product.model]: [Pixel 6]"
        assertEquals("Unknown", ToolHelpers.extractProperty(output, "ro.build.version.sdk"))
    }

    @Test
    fun `extractProperty handles empty output`() {
        assertEquals("Unknown", ToolHelpers.extractProperty("", "ro.product.model"))
    }

    @Test
    fun `extractProperty finds correct value among multiple properties`() {
        val output = """
            [ro.product.model]: [Pixel 6]
            [ro.build.version.release]: [14]
            [ro.build.version.sdk]: [34]
        """.trimIndent()
        assertEquals("14", ToolHelpers.extractProperty(output, "ro.build.version.release"))
        assertEquals("34", ToolHelpers.extractProperty(output, "ro.build.version.sdk"))
    }

    // --- extractPattern ---

    @Test
    fun `extractPattern returns matched group`() {
        assertEquals("1.0.0", ToolHelpers.extractPattern("versionName=1.0.0", "versionName=(\\S+)"))
    }

    @Test
    fun `extractPattern returns Unknown on no match`() {
        assertEquals("Unknown", ToolHelpers.extractPattern("no match here", "versionName=(\\S+)"))
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

        val result = ToolHelpers.formatAppInfo(rawInfo, "com.example.app")
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
        val result = ToolHelpers.formatAppInfo("", "com.test")
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

        val result = ToolHelpers.formatAppInfo(rawInfo, "com.test")
        // Should contain at most 10 permission entries
        val permCount = Regex("android\\.permission\\.PERM_").findAll(result).count()
        assertTrue(permCount <= 10, "Expected at most 10 permissions, got $permCount")
    }
}
