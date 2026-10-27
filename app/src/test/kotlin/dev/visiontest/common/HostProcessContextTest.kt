package dev.visiontest.common

import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.config.AppConfig
import dev.visiontest.config.LogLevel
import dev.visiontest.discovery.ToolDiscovery
import dev.visiontest.ios.ProcessExecutor
import dev.visiontest.android.AndroidHostProcesses
import dev.visiontest.recording.JvmRecordingProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostProcessContextTest {
    @TempDir lateinit var root: Path

    @Test
    fun `retained process executor uses each caller PATH cwd and environment`() {
        val executor = ProcessExecutor()
        for (name in listOf("first", "second")) {
            val directory = Files.createDirectory(root.resolve(name))
            val executable = Files.writeString(directory.resolve("request-tool"),
                "#!/bin/sh\nprintf '%s|%s' \"\$PWD\" \"\$REQUEST_VALUE\"\n")
            executable.toFile().setExecutable(true)
            val context = CliExecutionContext(directory, mapOf("PATH" to directory.toString(), "REQUEST_VALUE" to name))
            val result = runBlocking(context.coroutineContext()) {
                withContext(Dispatchers.IO) { executor.execute("request-tool") }
            }
            assertEquals(0, result.exitCode)
            assertEquals("${directory.toRealPath()}|$name", result.output)
        }
        assertNull(CliExecutionContext.currentOrNull())
    }

    @Test
    fun `default Android host launches and startup use caller adb`() {
        val marker = root.resolve("started.txt")
        Files.writeString(root.resolve("adb"), "#!/bin/sh\nprintf '%s' \"\$*|\$REQUEST_VALUE\" > started.txt\n")
            .toFile().setExecutable(true)
        val context = CliExecutionContext(root, mapOf("PATH" to root.toString(), "REQUEST_VALUE" to "caller"))
        runBlocking(context.coroutineContext()) {
            AndroidHostProcesses().startAdb()
            assertEquals("-P 5037 start-server|caller", Files.readString(marker))
            val process = AndroidHostProcesses().launchAdb(listOf("adb", "devices"))
            assertEquals(0, process.waitFor())
            assertEquals("devices|caller", Files.readString(marker))
        }
    }

    @Test
    fun `Android startup discovers each caller SDK without adb on PATH`() {
        for (variable in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val sdk = Files.createDirectories(root.resolve(variable).resolve("platform-tools"))
            Files.writeString(sdk.resolve("adb"),
                "#!/bin/sh\nprintf '%s' \"\$*|\$REQUEST_VALUE\" > started.txt\n")
                .toFile().setExecutable(true)
            val context = CliExecutionContext(root, mapOf(variable to variable, "REQUEST_VALUE" to variable))
            runBlocking(context.coroutineContext()) { AndroidHostProcesses().startAdb() }
            assertEquals("-P 5037 start-server|$variable", Files.readString(root.resolve("started.txt")))
        }
    }

    @Test
    fun `Android startup prefers caller Android home over SDK root and PATH`() {
        for (name in listOf("home", "sdk", "path")) {
            val bin = Files.createDirectories(root.resolve(name).resolve("platform-tools"))
            Files.writeString(bin.resolve("adb"), "#!/bin/sh\nprintf '%s' '$name' > started.txt\n")
                .toFile().setExecutable(true)
        }
        val context = CliExecutionContext(root, mapOf("ANDROID_HOME" to "home", "ANDROID_SDK_ROOT" to "sdk",
            "PATH" to root.resolve("path/platform-tools").toString()))
        runBlocking(context.coroutineContext()) { AndroidHostProcesses().startAdb() }
        assertEquals("home", Files.readString(root.resolve("started.txt")))
        runBlocking(context.copy(environment = context.environment - "ANDROID_HOME").coroutineContext()) {
            AndroidHostProcesses().startAdb()
        }
        assertEquals("sdk", Files.readString(root.resolve("started.txt")))
    }

    @Test
    fun `recording child uses request directory and environment`() {
        val context = CliExecutionContext(root, mapOf("PATH" to "/bin", "REQUEST_VALUE" to "recording"))
        val result = runBlocking(context.coroutineContext()) {
            JvmRecordingProcessRunner().run(listOf("sh", "-c", "printf '%s|%s' \"\$PWD\" \"\$REQUEST_VALUE\""),
                RequestDeadline(5000))
        }
        assertEquals("${root.toRealPath()}|recording", result.stdout.decodeToString())
    }

    @Test
    fun `retained discovery resolves each caller environment and relative overrides`() {
        val discovery = ToolDiscovery(LoggerFactory.getLogger(javaClass))
        for (name in listOf("first", "second")) {
            val directory = Files.createDirectory(root.resolve(name))
            val apk = Files.writeString(directory.resolve("test.apk"), name)
            val context = CliExecutionContext(directory, mapOf("VISION_TEST_APK_PATH" to "test.apk",
                "VISIONTEST_DIR" to "install"))
            context.withCurrent {
                assertEquals(apk.toString(), discovery.findAutomationServerApk())
                assertEquals(directory.resolve("install").toFile(), discovery.resolveInstallDir())
            }
        }
    }

    @Test
    fun `configuration uses active caller environment`() {
        CliExecutionContext(environment = mapOf("VISION_TEST_LOG_LEVEL" to "DEBUG")).withCurrent {
            assertEquals(LogLevel.DEBUG, AppConfig.createDefault().logLevel)
        }
        CliExecutionContext(environment = emptyMap()).withCurrent {
            assertEquals(LogLevel.PRODUCTION, AppConfig.createDefault().logLevel)
        }
    }
}
