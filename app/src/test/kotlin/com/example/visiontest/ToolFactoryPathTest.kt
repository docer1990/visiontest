package com.example.visiontest

import com.example.visiontest.common.DeviceConfig
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolFactoryPathTest {

    private val logger = LoggerFactory.getLogger("test")
    private val mockAndroid = mockk<DeviceConfig>(relaxed = true)
    private val mockIos = mockk<DeviceConfig>(relaxed = true)

    private val factory = ToolFactory(
        android = mockAndroid,
        ios = mockIos,
        logger = logger
    )

    companion object {
        private const val APK_RELATIVE_PATH =
            "automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk"
    }

    /** Creates the expected APK file inside [root] and returns it. */
    private fun createApkIn(root: File): File {
        val apkFile = File(root, APK_RELATIVE_PATH)
        apkFile.parentFile.mkdirs()
        apkFile.createNewFile()
        return apkFile
    }

    // ==================== findProjectRoot ====================

    @Test
    fun `findProjectRoot returns directory containing settings gradle kts`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()
        val subDir = File(tempDir, "app/src").apply { mkdirs() }

        val result = factory.findProjectRoot(subDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot returns directory containing settings gradle groovy`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle").createNewFile()
        val subDir = File(tempDir, "module/deep").apply { mkdirs() }

        val result = factory.findProjectRoot(subDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot returns null when no settings gradle found within 10 levels`(@TempDir tempDir: File) {
        var dir = tempDir
        repeat(12) { i ->
            dir = File(dir, "level$i").apply { mkdirs() }
        }

        val result = factory.findProjectRoot(dir)

        assertNull(result)
    }

    @Test
    fun `findProjectRoot handles startFrom being the root itself`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()

        val result = factory.findProjectRoot(tempDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot handles trailing dot in path`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()
        val dotDir = File(tempDir, ".")

        val result = factory.findProjectRoot(dotDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot finds root when both kts and groovy exist`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()
        File(tempDir, "settings.gradle").createNewFile()
        val subDir = File(tempDir, "sub").apply { mkdirs() }

        val result = factory.findProjectRoot(subDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot finds root one level up`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()
        val child = File(tempDir, "app").apply { mkdirs() }

        val result = factory.findProjectRoot(child)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    // ==================== findAutomationServerApk ====================

    @Test
    fun `findAutomationServerApk returns null when no APK and no env var`(@TempDir tempDir: File) {
        val result = factory.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(tempDir)
        )

        assertNull(result)
    }

    @Test
    fun `findAutomationServerApk returns env var path when file exists`(@TempDir tempDir: File) {
        val apkFile = File(tempDir, "custom.apk").apply {
            parentFile.mkdirs()
            createNewFile()
        }

        val result = factory.findAutomationServerApk(
            envApkPath = apkFile.absolutePath,
            searchRoots = emptyList()
        )

        assertNotNull(result)
        assertEquals(apkFile.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk returns null when env var points to missing file`(@TempDir tempDir: File) {
        val result = factory.findAutomationServerApk(
            envApkPath = File(tempDir, "nonexistent.apk").absolutePath,
            searchRoots = emptyList()
        )

        assertNull(result)
    }

    @Test
    fun `findAutomationServerApk finds APK relative to search root`(@TempDir tempDir: File) {
        val apkFile = createApkIn(tempDir)

        val result = factory.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(tempDir)
        )

        assertNotNull(result)
        assertEquals(apkFile.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk prefers env var over search roots`(@TempDir tempDir: File) {
        // Both env var file and search root APK exist
        val envApk = File(tempDir, "env-apk.apk").apply { createNewFile() }
        val searchRoot = File(tempDir, "project").apply { mkdirs() }
        createApkIn(searchRoot)

        val result = factory.findAutomationServerApk(
            envApkPath = envApk.absolutePath,
            searchRoots = listOf(searchRoot)
        )

        assertNotNull(result)
        assertEquals(envApk.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk falls through to search roots when env var file missing`(@TempDir tempDir: File) {
        val searchRoot = File(tempDir, "project").apply { mkdirs() }
        val apkFile = createApkIn(searchRoot)

        val result = factory.findAutomationServerApk(
            envApkPath = File(tempDir, "missing.apk").absolutePath,
            searchRoots = listOf(searchRoot)
        )

        assertNotNull(result)
        assertEquals(apkFile.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk tries search roots in order`(@TempDir tempDir: File) {
        val first = File(tempDir, "first").apply { mkdirs() }
        val second = File(tempDir, "second").apply { mkdirs() }
        val firstApk = createApkIn(first)
        createApkIn(second)

        val result = factory.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(first, second)
        )

        assertNotNull(result)
        assertEquals(firstApk.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk skips roots without APK`(@TempDir tempDir: File) {
        val emptyRoot = File(tempDir, "empty").apply { mkdirs() }
        val validRoot = File(tempDir, "valid").apply { mkdirs() }
        val apkFile = createApkIn(validRoot)

        val result = factory.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(emptyRoot, validRoot)
        )

        assertNotNull(result)
        assertEquals(apkFile.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk returns null with empty search roots and no env var`() {
        val result = factory.findAutomationServerApk(
            envApkPath = null,
            searchRoots = emptyList()
        )

        assertNull(result)
    }

    @Test
    fun `findAutomationServerApk returned path ends with expected APK filename`(@TempDir tempDir: File) {
        createApkIn(tempDir)

        val result = factory.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(tempDir)
        )

        assertNotNull(result)
        assertTrue(result.endsWith("automation-server-debug-androidTest.apk"))
    }
}
