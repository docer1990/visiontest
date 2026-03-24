package com.example.visiontest

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.tools.IOSAutomationToolRegistrar
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
    private val discovery = ToolDiscovery(logger)

    private val iosRegistrar = IOSAutomationToolRegistrar(
        ios = mockk<DeviceConfig>(relaxed = true),
        iosAutomationClient = mockk<IOSAutomationClient>(relaxed = true),
        discovery = discovery,
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

        val result = discovery.findProjectRoot(subDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot returns directory containing settings gradle groovy`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle").createNewFile()
        val subDir = File(tempDir, "module/deep").apply { mkdirs() }

        val result = discovery.findProjectRoot(subDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot returns null when no settings gradle found within 10 levels`(@TempDir tempDir: File) {
        var dir = tempDir
        repeat(12) { i ->
            dir = File(dir, "level$i").apply { mkdirs() }
        }

        val result = discovery.findProjectRoot(dir)

        assertNull(result)
    }

    @Test
    fun `findProjectRoot handles startFrom being the root itself`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()

        val result = discovery.findProjectRoot(tempDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot handles trailing dot in path`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()
        val dotDir = File(tempDir, ".")

        val result = discovery.findProjectRoot(dotDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot finds root when both kts and groovy exist`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()
        File(tempDir, "settings.gradle").createNewFile()
        val subDir = File(tempDir, "sub").apply { mkdirs() }

        val result = discovery.findProjectRoot(subDir)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    @Test
    fun `findProjectRoot finds root one level up`(@TempDir tempDir: File) {
        File(tempDir, "settings.gradle.kts").createNewFile()
        val child = File(tempDir, "app").apply { mkdirs() }

        val result = discovery.findProjectRoot(child)

        assertNotNull(result)
        assertEquals(tempDir.absolutePath, result.absolutePath)
    }

    // ==================== findAutomationServerApk ====================

    @Test
    fun `findAutomationServerApk returns null when no APK and no env var`(@TempDir tempDir: File) {
        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(tempDir)
        )

        assertNull(result)
    }

    @Test
    fun `findAutomationServerApk returns env var path when file exists`(@TempDir tempDir: File) {
        val apkFile = File(tempDir, "custom.apk").apply { createNewFile() }

        val result = discovery.findAutomationServerApk(
            envApkPath = apkFile.absolutePath,
            searchRoots = emptyList()
        )

        assertNotNull(result)
        assertEquals(apkFile.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk returns null when env var points to missing file`(@TempDir tempDir: File) {
        val result = discovery.findAutomationServerApk(
            envApkPath = File(tempDir, "nonexistent.apk").absolutePath,
            searchRoots = emptyList()
        )

        assertNull(result)
    }

    @Test
    fun `findAutomationServerApk finds APK relative to search root`(@TempDir tempDir: File) {
        val apkFile = createApkIn(tempDir)

        val result = discovery.findAutomationServerApk(
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

        val result = discovery.findAutomationServerApk(
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

        val result = discovery.findAutomationServerApk(
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

        val result = discovery.findAutomationServerApk(
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

        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(emptyRoot, validRoot)
        )

        assertNotNull(result)
        assertEquals(apkFile.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk returns null with empty search roots and no env var`() {
        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = emptyList()
        )

        assertNull(result)
    }

    @Test
    fun `findAutomationServerApk returned path ends with expected APK filename`(@TempDir tempDir: File) {
        createApkIn(tempDir)

        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(tempDir)
        )

        assertNotNull(result)
        assertTrue(result.endsWith("automation-server-debug-androidTest.apk"))
    }

    // ==================== findAutomationServerApk — installDir ====================

    @Test
    fun `findAutomationServerApk finds APK in installDir`(@TempDir tempDir: File) {
        val installDir = File(tempDir, "install").apply { mkdirs() }
        File(installDir, "automation-server-test.apk").createNewFile()

        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = emptyList(),
            installDir = installDir
        )

        assertNotNull(result)
        assertTrue(result.endsWith("automation-server-test.apk"))
    }

    @Test
    fun `findAutomationServerApk prefers env var over installDir`(@TempDir tempDir: File) {
        val envApk = File(tempDir, "env-apk.apk").apply { createNewFile() }
        val installDir = File(tempDir, "install").apply { mkdirs() }
        File(installDir, "automation-server-test.apk").createNewFile()

        val result = discovery.findAutomationServerApk(
            envApkPath = envApk.absolutePath,
            searchRoots = emptyList(),
            installDir = installDir
        )

        assertNotNull(result)
        assertEquals(envApk.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk prefers search roots over installDir`(@TempDir tempDir: File) {
        val searchRoot = File(tempDir, "project").apply { mkdirs() }
        val gradleApk = createApkIn(searchRoot)
        val installDir = File(tempDir, "install").apply { mkdirs() }
        File(installDir, "automation-server-test.apk").createNewFile()

        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = listOf(searchRoot),
            installDir = installDir
        )

        assertNotNull(result)
        assertEquals(gradleApk.absolutePath, result)
    }

    @Test
    fun `findAutomationServerApk returns null when installDir has no APK`(@TempDir tempDir: File) {
        val installDir = File(tempDir, "install").apply { mkdirs() }

        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = emptyList(),
            installDir = installDir
        )

        assertNull(result)
    }

    @Test
    fun `findAutomationServerApk returns null when installDir is null`() {
        val result = discovery.findAutomationServerApk(
            envApkPath = null,
            searchRoots = emptyList(),
            installDir = null
        )

        assertNull(result)
    }

    // ==================== resolveMainApkPath ====================

    @Test
    fun `resolveMainApkPath derives main APK from Gradle androidTest path`(@TempDir tempDir: File) {
        // Simulate Gradle build output structure
        val testApkDir = File(tempDir, "apk/androidTest/debug").apply { mkdirs() }
        val mainApkDir = File(tempDir, "apk/debug").apply { mkdirs() }
        val testApk = File(testApkDir, "automation-server-debug-androidTest.apk").apply { createNewFile() }
        val mainApk = File(mainApkDir, "automation-server-debug.apk").apply { createNewFile() }

        val result = discovery.resolveMainApkPath(testApk.absolutePath)

        assertNotNull(result)
        assertEquals(mainApk.absolutePath, result)
    }

    @Test
    fun `resolveMainApkPath resolves sibling main APK for install-dir test APK`(@TempDir tempDir: File) {
        val testApk = File(tempDir, "automation-server-test.apk").apply { createNewFile() }
        val mainApk = File(tempDir, "automation-server.apk").apply { createNewFile() }

        val result = discovery.resolveMainApkPath(testApk.absolutePath)

        assertNotNull(result)
        assertEquals(mainApk.absolutePath, result)
    }

    @Test
    fun `resolveMainApkPath returns null when no main APK exists`(@TempDir tempDir: File) {
        val testApk = File(tempDir, "automation-server-test.apk").apply { createNewFile() }

        val result = discovery.resolveMainApkPath(testApk.absolutePath)

        assertNull(result)
    }

    @Test
    fun `resolveMainApkPath does not return test APK as main APK when replacements are no-ops`(@TempDir tempDir: File) {
        // automation-server-test.apk has no "androidTest/" or "-androidTest" to strip,
        // so the derived path equals the input — should NOT treat test APK as main APK
        val testApk = File(tempDir, "automation-server-test.apk").apply { createNewFile() }

        val result = discovery.resolveMainApkPath(testApk.absolutePath)

        // Without a sibling automation-server.apk, result should be null
        assertNull(result)
    }

    @Test
    fun `resolveMainApkPath returns null for bare filename with no parent directory`() {
        val result = discovery.resolveMainApkPath("test.apk")

        assertNull(result)
    }

    // ==================== findXctestrun ====================

    @Test
    fun `findXctestrun finds xctestrun file in install directory`(@TempDir tempDir: File) {
        val bundleDir = File(tempDir, "ios-automation-server").apply { mkdirs() }
        File(bundleDir, "IOSAutomationServer_iphonesimulator18.0-arm64.xctestrun").createNewFile()

        val result = discovery.findXctestrun(tempDir)

        assertNotNull(result)
        assertTrue(result.endsWith(".xctestrun"))
    }

    @Test
    fun `findXctestrun returns null when install directory has no xctestrun`(@TempDir tempDir: File) {
        File(tempDir, "ios-automation-server").mkdirs()

        val result = discovery.findXctestrun(tempDir)

        assertNull(result)
    }

    @Test
    fun `findXctestrun returns null when install directory does not exist`(@TempDir tempDir: File) {
        val nonExistent = File(tempDir, "nonexistent")

        val result = discovery.findXctestrun(nonExistent)

        assertNull(result)
    }

    @Test
    fun `findXctestrun selects first file alphabetically when multiple xctestrun files exist`(@TempDir tempDir: File) {
        val bundleDir = File(tempDir, "ios-automation-server").apply { mkdirs() }
        File(bundleDir, "B_iphonesimulator18.0.xctestrun").createNewFile()
        File(bundleDir, "A_iphonesimulator17.0.xctestrun").createNewFile()

        val result = discovery.findXctestrun(tempDir)

        assertNotNull(result)
        assertTrue(result.contains("A_iphonesimulator17.0.xctestrun"))
    }

    @Test
    fun `findXctestrun returns absolute path`(@TempDir tempDir: File) {
        val bundleDir = File(tempDir, "ios-automation-server").apply { mkdirs() }
        File(bundleDir, "Test.xctestrun").createNewFile()

        val result = discovery.findXctestrun(tempDir)

        assertNotNull(result)
        assertTrue(File(result).isAbsolute)
    }

    @Test
    fun `findXctestrun ignores non-xctestrun files`(@TempDir tempDir: File) {
        val bundleDir = File(tempDir, "ios-automation-server").apply { mkdirs() }
        File(bundleDir, "IOSAutomationServer.app").mkdirs()
        File(bundleDir, "readme.txt").createNewFile()

        val result = discovery.findXctestrun(tempDir)

        assertNull(result)
    }

    // ==================== buildXcodebuildCommand ====================

    @Test
    fun `buildXcodebuildCommand produces test-without-building for pre-built path`() {
        val command = iosRegistrar.buildXcodebuildCommand(
            xctestrunPath = "/path/to/Test.xctestrun",
            projectPath = null,
            simulatorName = "iPhone 16"
        )

        assertEquals("xcodebuild", command[0])
        assertEquals("test-without-building", command[1])
        assertTrue(command.contains("-xctestrun"))
        assertTrue(command.contains("/path/to/Test.xctestrun"))
        assertTrue(command.contains("platform=iOS Simulator,name=iPhone 16"))
    }

    @Test
    fun `buildXcodebuildCommand produces test for source path`() {
        val command = iosRegistrar.buildXcodebuildCommand(
            xctestrunPath = null,
            projectPath = "/path/to/Project.xcodeproj",
            simulatorName = "iPhone 16"
        )

        assertEquals("xcodebuild", command[0])
        assertEquals("test", command[1])
        assertTrue(command.contains("-project"))
        assertTrue(command.contains("/path/to/Project.xcodeproj"))
        assertTrue(command.contains("-scheme"))
        assertTrue(command.contains("platform=iOS Simulator,name=iPhone 16"))
    }
}
