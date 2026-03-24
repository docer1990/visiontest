package com.example.visiontest.discovery

import com.example.visiontest.config.IOSAutomationConfig
import org.slf4j.Logger
import java.io.File

class ToolDiscovery(private val logger: Logger) {

    // ==================== Xcode Project Discovery ====================

    internal fun isValidXcodeProjectPath(file: File): Boolean {
        return file.exists() && file.isDirectory && file.name.endsWith(".xcodeproj")
    }

    fun findXcodeProject(): String? {
        // 0. Check environment variable first (allows explicit override)
        System.getenv(IOSAutomationConfig.XCODE_PROJECT_PATH_ENV)?.let { envPath ->
            val envFile = File(envPath)
            if (isValidXcodeProjectPath(envFile)) {
                logger.info("Using Xcode project from ${IOSAutomationConfig.XCODE_PROJECT_PATH_ENV}: $envPath")
                return envFile.absolutePath
            }
            if (envFile.exists()) {
                logger.warn("${IOSAutomationConfig.XCODE_PROJECT_PATH_ENV} path is not a valid .xcodeproj directory: $envPath")
            } else {
                logger.warn("${IOSAutomationConfig.XCODE_PROJECT_PATH_ENV} set but path not found: $envPath")
            }
        }

        val relativePath = IOSAutomationConfig.XCODE_PROJECT_PATH

        // 1. Try relative to current working directory
        val cwdFile = File(relativePath)
        if (isValidXcodeProjectPath(cwdFile)) {
            return cwdFile.absolutePath
        }

        // 2. Try relative to project root
        val projectRoot = findProjectRoot(File(".").absoluteFile)
        if (projectRoot != null) {
            val projectFile = File(projectRoot, relativePath)
            if (isValidXcodeProjectPath(projectFile)) {
                return projectFile.absolutePath
            }
        }

        // 3. Try relative to code source
        val codeSourceRoot = findCodeSourceRoot()
        if (codeSourceRoot != null) {
            val codeSourceFile = File(codeSourceRoot, relativePath)
            if (isValidXcodeProjectPath(codeSourceFile)) {
                return codeSourceFile.absolutePath
            }
        }

        return null
    }

    // ==================== Install Directory Resolution ====================

    /**
     * Resolves the install directory from VISIONTEST_DIR env var, JAR directory, or default.
     * Shared by APK discovery and iOS xctestrun discovery.
     */
    internal fun resolveInstallDir(): File {
        val installDirPath = System.getenv("VISIONTEST_DIR")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findJarDirectory()?.absolutePath
            ?: "${System.getProperty("user.home")}/.local/share/visiontest"
        return File(installDirPath)
    }

    // ==================== Android APK Discovery ====================

    fun findAutomationServerApk(): String? {
        val cwd = File(".").absoluteFile
        val codeSourceRoot = findCodeSourceRoot()
        return findAutomationServerApk(
            envApkPath = System.getenv("VISION_TEST_APK_PATH"),
            searchRoots = listOfNotNull(cwd, codeSourceRoot, findProjectRoot(cwd)),
            installDir = resolveInstallDir()
        )
    }

    /**
     * Given the path to a test APK, resolves the corresponding main APK path.
     *
     * For Gradle build output (androidTest path), derives the main APK by stripping "androidTest/" and "-androidTest".
     * For install-dir APKs (e.g. automation-server-test.apk), looks for a sibling automation-server.apk.
     * Returns null if no main APK can be found.
     */
    internal fun resolveMainApkPath(testApkPath: String): String? {
        // Derive main APK path from Gradle androidTest layout
        val derivedPath = testApkPath
            .replaceFirst("androidTest/", "")
            .replaceFirst("-androidTest", "")
        val derivedFile = File(derivedPath)
        val isSamePath = derivedPath == testApkPath
        val isKnownTestName = testApkPath.endsWith("automation-server-test.apk")

        if (derivedFile.exists() && !isSamePath && !isKnownTestName) {
            return derivedPath
        }

        // Fallback: check for simple-named APK in the same directory (install dir)
        val parent = File(testApkPath).parentFile ?: return null
        val siblingApk = File(parent, "automation-server.apk")
        return if (siblingApk.exists()) siblingApk.absolutePath else null
    }

    /**
     * Returns the directory containing the running JAR, or null if not running from a JAR.
     * Used to discover APKs co-located with the JAR in custom install directories.
     *
     * Uses this class's code source — works because ToolDiscovery is in the same JAR as ToolFactory.
     */
    private fun findJarDirectory(): File? {
        return try {
            val location = this::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { File(it) }
            if (location != null && location.isFile && location.name.endsWith(".jar")) {
                location.parentFile
            } else null
        } catch (e: Exception) {
            logger.debug("Could not determine JAR directory: ${e.message}")
            null
        }
    }

    internal fun findAutomationServerApk(
        envApkPath: String?,
        searchRoots: List<File>,
        installDir: File? = null
    ): String? {
        val apkRelativePath = "automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk"

        logger.debug("Searching for automation server APK...")

        // 1. Check environment variable first (allows explicit configuration)
        envApkPath?.let { envPath ->
            val file = File(envPath)
            if (file.exists()) {
                logger.info("Using APK from VISION_TEST_APK_PATH: $envPath")
                return file.absolutePath
            }
            logger.warn("VISION_TEST_APK_PATH set but file not found: $envPath")
        }

        // 2. Try relative to each search root (CWD, code source, project root)
        for (root in searchRoots) {
            val apkFile = File(root, apkRelativePath)
            logger.debug("Checking path: ${apkFile.absolutePath}")
            if (apkFile.exists()) {
                logger.info("Found APK at: ${apkFile.absolutePath}")
                return apkFile.absolutePath
            }
        }

        // 3. Try install directory as lowest-priority fallback
        if (installDir != null) {
            val installedApk = File(installDir, "automation-server-test.apk")
            if (installedApk.exists()) {
                logger.info("Found APK in install directory: ${installedApk.absolutePath}")
                return installedApk.absolutePath
            }
        }

        logger.warn("APK not found in ${searchRoots.size} search roots.")
        logger.warn("Re-run install.sh to download APKs, or set VISION_TEST_APK_PATH environment variable.")
        return null
    }

    // ==================== iOS xctestrun Discovery ====================

    fun findXctestrun(): String? {
        return findXctestrun(resolveInstallDir())
    }

    /**
     * Searches for a pre-built .xctestrun file in the install directory's
     * ios-automation-server/ subdirectory.
     *
     * Returns the absolute path to the first .xctestrun file found (sorted alphabetically),
     * or null if none exists.
     */
    internal fun findXctestrun(installDir: File): String? {
        val bundleDir = File(installDir, IOSAutomationConfig.XCTESTRUN_BUNDLE_DIR)
        logger.debug("Searching for .xctestrun in: ${bundleDir.absolutePath}")

        if (!bundleDir.isDirectory) {
            logger.debug("iOS bundle directory does not exist: ${bundleDir.absolutePath}")
            return null
        }

        val xctestrunFiles = bundleDir.listFiles { file ->
            file.isFile && file.name.endsWith(".xctestrun")
        }?.sortedBy { it.name }

        if (xctestrunFiles.isNullOrEmpty()) {
            logger.debug("No .xctestrun files found in: ${bundleDir.absolutePath}")
            return null
        }

        if (xctestrunFiles.size > 1) {
            logger.info("Multiple .xctestrun files found, using first: ${xctestrunFiles.first().name}")
        }

        val result = xctestrunFiles.first().absolutePath
        logger.info("Found xctestrun: $result")
        return result
    }

    // ==================== Project Root Discovery ====================

    // Uses this class's code source — works because ToolDiscovery is in the same JAR as ToolFactory.
    internal fun findCodeSourceRoot(): File? {
        return try {
            val codeSource = this::class.java.protectionDomain?.codeSource
            val location = codeSource?.location?.toURI()?.let { File(it) }

            if (location != null) {
                logger.debug("Code source location: ${location.absolutePath}")

                // If running from JAR (app/build/libs/visiontest.jar), go up 3 levels to project root
                if (location.isFile && location.name.endsWith(".jar")) {
                    return location.parentFile?.parentFile?.parentFile
                }

                // If running from classes dir (app/build/classes/kotlin/main), go up 5 levels
                if (location.isDirectory && location.path.contains("build/classes")) {
                    return location.parentFile?.parentFile?.parentFile?.parentFile?.parentFile
                }

                // Try going up until we find settings.gradle.kts
                return findProjectRoot(location)
            }
            null
        } catch (e: Exception) {
            logger.debug("Could not determine code source location: ${e.message}")
            null
        }
    }

    internal fun findProjectRoot(startFrom: File): File? {
        var current = startFrom.absoluteFile
        // Handle trailing "." in path
        if (current.name == ".") {
            current = current.parentFile ?: return null
        }

        repeat(10) {
            val settingsKts = File(current, "settings.gradle.kts")
            val settingsGroovy = File(current, "settings.gradle")
            logger.debug("Checking for settings.gradle in: ${current.absolutePath}")

            if (settingsKts.exists() || settingsGroovy.exists()) {
                logger.debug("Found project root: ${current.absolutePath}")
                return current
            }
            current = current.parentFile ?: return null
        }
        return null
    }
}
