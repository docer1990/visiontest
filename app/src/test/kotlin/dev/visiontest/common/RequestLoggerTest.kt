package dev.visiontest.common

import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.cli.CliRequestExecutor
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.ios.ProcessExecutor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import dev.visiontest.utils.ErrorHandler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RequestLoggerTest {
    @TempDir lateinit var root: Path

    @Test
    fun `explicit stdout destination is returned before operation output`() {
        val result = executeWarning(CliExecutionContext(workingDirectory = root,
            loggingProperties = mapOf("org.slf4j.simpleLogger.logFile" to "System.out")))
        assertEquals("WARN destination - warning\ndone", result.stdout)
        assertNull(result.stderr)
    }

    @Test
    fun `standard stream destinations are case insensitive`() {
        val result = executeWarning(CliExecutionContext(workingDirectory = root,
            loggingProperties = mapOf("org.slf4j.simpleLogger.logFile" to "system.OUT")))
        assertEquals("WARN destination - warning\ndone", result.stdout)
        assertNull(result.stderr)
        assertFalse(Files.exists(root.resolve("system.OUT")))
    }

    @Test
    fun `unknown configured level falls back to info as SimpleLogger does`() {
        val logger = RequestLogger.getLogger("example.category")
        CliExecutionContext(loggingProperties = mapOf(
            "org.slf4j.simpleLogger.defaultLogLevel" to "invalid",
        )).withCurrent {
            assertTrue(logger.isInfoEnabled)
            assertFalse(logger.isDebugEnabled)
        }
    }

    @Test
    fun `marker names retain SimpleLogger message formatting`() {
        val messages = mutableListOf<String>()
        CliExecutionContext(diagnostic = messages::add).withCurrent {
            RequestLogger.getLogger("example.category").warn(org.slf4j.MarkerFactory.getMarker("request"), "warning")
        }
        assertEquals(listOf("WARN category -  request warning"), messages)
    }

    @Test
    fun `file destination resolves independently for successive callers`() {
        for (name in listOf("first", "second")) {
            val directory = Files.createDirectory(root.resolve(name))
            val result = executeWarning(CliExecutionContext(workingDirectory = directory,
                loggingProperties = mapOf("org.slf4j.simpleLogger.logFile" to "request.log")))
            assertEquals("done", result.stdout)
            assertNull(result.stderr)
            assertEquals("WARN destination - warning\n", Files.readString(directory.resolve("request.log")))
        }
    }

    @Test
    fun `unwritable file destination falls back to request stderr`() {
        val result = executeWarning(CliExecutionContext(workingDirectory = root,
            loggingProperties = mapOf("org.slf4j.simpleLogger.logFile" to "missing/request.log")))
        assertEquals("done", result.stdout)
        assertContains(result.stderr.orEmpty(), "Failed to open [missing/request.log]")
        assertContains(result.stderr.orEmpty(), "WARN destination - warning")
    }

    private fun executeWarning(context: CliExecutionContext): dev.visiontest.cli.CliResult {
        val components = mockk<ComponentHolder>(relaxed = true)
        coEvery { components.isServerRunning(Platform.Android) } returns true
        coEvery { components.androidAutomationRegistrar.pressHome() } coAnswers {
            withContext(Dispatchers.IO) { RequestLogger.getLogger("destination").warn("warning") }
            "done"
        }
        return CliRequestExecutor().execute(arrayOf("press_home", "--platform", "android"), context, { components })
    }

    @Test
    fun `caller logging overrides never reuse daemon levels or format`() {
        val logger = RequestLogger.getLogger("example.retained.Logger")
        val messages = mutableListOf<String>()
        val request = CliExecutionContext(diagnostic = messages::add, loggingProperties = mapOf(
            "org.slf4j.simpleLogger.defaultLogLevel" to "debug",
            "org.slf4j.simpleLogger.showShortLogName" to "false",
            "org.slf4j.simpleLogger.levelInBrackets" to "true",
        ))
        request.withCurrent {
            assertTrue(logger.isDebugEnabled)
            logger.debug("value {}", "first")
        }
        assertEquals(listOf("[DEBUG] example.retained.Logger - value first"), messages)
        request.copy(loggingProperties = emptyMap()).withCurrent {
            assertFalse(logger.isDebugEnabled)
            logger.debug("hidden")
            logger.warn("value {}", "second")
        }
        assertEquals("WARN Logger - value second", messages.last())
        assertEquals(2, messages.size)
    }

    @Test
    fun `worker logging keeps owning context and restores enclosing context`() = runBlocking {
        val logger = RequestLogger.getLogger(RequestLoggerTest::class.java)
        val outer = CliExecutionContext(environment = mapOf("owner" to "outer"))
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        withContext(outer.coroutineContext()) {
            listOf(first, second).mapIndexed { index, messages ->
                async(Dispatchers.IO + outer.copy(diagnostic = messages::add).coroutineContext()) {
                    logger.warn("worker {}", index)
                    withContext(Dispatchers.Default) { logger.warn("resumed {}", index) }
                }
            }.forEach { it.await() }
            assertEquals(outer, CliExecutionContext.currentOrNull())
        }
        assertEquals(listOf("WARN RequestLoggerTest - worker 0", "WARN RequestLoggerTest - resumed 0"), first)
        assertEquals(listOf("WARN RequestLoggerTest - worker 1", "WARN RequestLoggerTest - resumed 1"), second)
        assertNull(CliExecutionContext.currentOrNull())
    }

    @Test
    fun `request log formatting retains throwable details and reads error policy per caller`() {
        val logger = RequestLogger.getLogger(javaClass)
        val messages = mutableListOf<String>()
        val request = CliExecutionContext(environment = mapOf("VISION_TEST_LOG_LEVEL" to "DEBUG"),
            diagnostic = messages::add)
        request.withCurrent { ErrorHandler.handleToolError(IllegalStateException("failure"), logger, "request") }
        assertContains(messages.joinToString("\n"), "java.lang.IllegalStateException: failure")
        messages.clear()
        request.copy(environment = emptyMap()).withCurrent {
            ErrorHandler.handleToolError(IllegalStateException("failure"), logger, "request")
        }
        assertEquals(1, messages.size)
        assertFalse(messages.single().contains("IllegalStateException"))
    }

    @Test
    fun `retained worker logger sends each invocation diagnostics to its result once`() {
        val process = ProcessExecutor()
        val components = mockk<ComponentHolder>(relaxed = true)
        coEvery { components.isServerRunning(Platform.Android) } returns true
        var diagnostic = "first warning"
        coEvery { components.androidAutomationRegistrar.pressHome() } coAnswers {
            withContext(Dispatchers.IO) {
                process.execute("/bin/sh", "-c", "printf '%s' '$diagnostic' >&2; exit 1")
            }
            "done"
        }
        val executor = CliRequestExecutor()
        for (message in listOf("first warning", "second warning")) {
            diagnostic = message
            val immediate = mutableListOf<String>()
            val result = executor.execute(arrayOf("press_home", "--platform", "android"),
                CliExecutionContext(diagnostic = immediate::add), { components })
            assertEquals(0, result.exitCode)
            assertContains(result.stderr.orEmpty(), "Command failed: $message")
            assertEquals(1, result.stderr.orEmpty().lines().count { message in it })
            assertEquals(emptyList(), immediate)
        }
        assertNull(CliExecutionContext.currentOrNull())
    }
}
