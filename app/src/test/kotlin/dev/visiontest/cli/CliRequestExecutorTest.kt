package dev.visiontest.cli

import dev.visiontest.config.VersionInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliRequestExecutorTest {
    private val executor = CliRequestExecutor()
    private val context = CliExecutionContext.local()
    private val forbiddenComponents: () -> ComponentHolder = { error("must not initialize") }

    @Test
    fun `invalid request does not prevent another request in the same JVM`() {
        val invalid = executor.execute(arrayOf("get_device_info"), context, forbiddenComponents)
        assertEquals(CliResult(2, null, "missing option --platform"), invalid)
        val version = executor.execute(arrayOf("--version"), context, forbiddenComponents)
        assertEquals(0, version.exitCode)
        assertContains(version.stdout!!, VersionInfo.version)
        assertNull(version.stderr)
    }

    @Test
    fun `root and command help return without initializing components`() {
        listOf(arrayOf("--help"), arrayOf("get_device_info", "--help")).forEach { args ->
            val result = executor.execute(args, context, forbiddenComponents)
            assertEquals(0, result.exitCode)
            assertContains(result.stdout!!, "Usage:")
            assertNull(result.stderr)
        }
    }

    @Test
    fun `usage errors preserve argument formatting`() {
        val result = executor.execute(arrayOf("tap_by_coordinates", "--platform", "android", "100"),
            context, forbiddenComponents)
        assertEquals(CliResult(2, null, "missing argument <Y>"), result)
    }

    @Test
    fun `android only rejection occurs before component initialization`() {
        val result = executor.execute(arrayOf("press_back", "--platform", "ios"), context, forbiddenComponents)
        assertEquals(5, result.exitCode)
        assertContains(result.stderr!!, "only supported on Android")
        assertNull(result.stdout)
    }

    @Test
    fun `parser options do not survive a previous request`() {
        val first = executor.execute(arrayOf("press_back", "--platform", "ios"), context, forbiddenComponents)
        assertEquals(5, first.exitCode)
        val second = executor.execute(arrayOf("press_back"), context, forbiddenComponents)
        assertEquals(CliResult(2, null, "missing option --platform"), second)
    }

    @Test
    fun `returned operation failure remains a successful standalone command result`() {
        val holder = actionComponents()
        val output = """{"jsonrpc":"2.0","id":1,"result":{"success":false,"error":"not available"}}"""
        coEvery { holder.androidAutomationRegistrar.pressHome() } returns output
        val result = executor.execute(arrayOf("press_home", "--platform", "android"), context, { holder })
        assertEquals(CliResult(0, output, null), result)
    }

    @Test
    fun `flow operation failure returns report and diagnostic`() {
        val holder = actionComponents()
        coEvery { holder.androidAutomationRegistrar.pressHome() } returns
            """{"jsonrpc":"2.0","id":1,"result":{"success":false,"error":"not available"}}"""
        val result = executor.execute(arrayOf("flow", "--platform", "android", "--step", "press_home", "--json"),
            context, { holder })
        assertEquals(1, result.exitCode)
        assertContains(result.stdout!!, "\"success\":false")
        assertEquals("not available", result.stderr)
    }

    @Test
    fun `execution coroutine context reaches command operations`() {
        val holder = actionComponents()
        var name: String? = null
        coEvery { holder.androidAutomationRegistrar.pressHome() } coAnswers {
            name = coroutineContext[CoroutineName]?.name
            """{"jsonrpc":"2.0","id":1,"result":{"success":true}}"""
        }
        val result = executor.execute(arrayOf("press_home", "--platform", "android"), context, { holder },
            CoroutineName("request-context"))
        assertEquals(0, result.exitCode)
        assertEquals("request-context", name)
    }

    @Test
    fun `local context snapshots an absolute working directory and environment`() {
        assertTrue(context.workingDirectory.isAbsolute)
        assertEquals(System.getenv(), context.environment)
    }

    private fun actionComponents(): ComponentHolder = mockk<ComponentHolder>(relaxed = true).also {
        coEvery { it.isServerRunning(Platform.Android) } returns true
    }
}
