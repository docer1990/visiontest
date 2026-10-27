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
