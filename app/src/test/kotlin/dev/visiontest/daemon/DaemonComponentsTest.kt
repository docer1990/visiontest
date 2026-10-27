package dev.visiontest.daemon

import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.CliRequestExecutor
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class DaemonComponentsTest {
    @Test fun `compatible requests share graph and close exactly once`() {
        val holder = mockk<ComponentHolder>(relaxed = true)
        var constructions = 0
        val resources = DaemonComponents(factory = { constructions++; holder })
        val context = CliExecutionContext(environment = mapOf("PATH" to "/bin"))
        assertSame(resources.acquire(context), resources.acquire(context.copy(workingDirectory = Path.of("/tmp"))))
        assertEquals(1, constructions)
        assertEquals(1, resources.initializationCount)
        resources.close()
        resources.close()
        verify(exactly = 1) { holder.close() }
        assertFailsWith<IllegalStateException> { resources.acquire(context) }
    }

    @Test fun `logging and unrelated caller environment do not replace resources`() {
        val holder = mockk<ComponentHolder>(relaxed = true)
        val resources = DaemonComponents(factory = { holder })
        val context = CliExecutionContext(environment = mapOf("PATH" to "/bin", "VISION_TEST_LOG_LEVEL" to "DEBUG"))
        assertSame(resources.acquire(context), resources.acquire(context.copy(
            environment = mapOf("PATH" to "/bin", "VISION_TEST_LOG_LEVEL" to "PRODUCTION", "REQUEST_DATA" to "value"),
            loggingProperties = mapOf("org.slf4j.simpleLogger.defaultLogLevel" to "debug"))))
        assertEquals(1, resources.initializationCount)
        resources.close()
    }

    @Test fun `tool environment replacement and invalidation dispose old resources`() {
        val first = mockk<ComponentHolder>(relaxed = true)
        val second = mockk<ComponentHolder>(relaxed = true)
        val third = mockk<ComponentHolder>(relaxed = true)
        val holders = ArrayDeque(listOf(first, second, third))
        val resources = DaemonComponents(factory = { holders.removeFirst() })
        val context = CliExecutionContext(environment = mapOf("PATH" to "/first"))
        assertSame(first, resources.acquire(context))
        assertSame(second, resources.acquire(context.copy(environment = mapOf("PATH" to "/second"))))
        resources.invalidate()
        resources.invalidate()
        assertSame(third, resources.acquire(context))
        resources.close()
        verify(exactly = 1) { first.close() }
        verify(exactly = 1) { second.close() }
        verify(exactly = 1) { third.close() }
    }

    @Test fun `request provider refreshes targets and replaces graph on target changes`() {
        var target = "first"
        var inventories = 0
        val guard = DaemonTargetGuard(androidInventory = { inventories++; listOf(target) }, androidForwards = { "" })
        val first = mockk<ComponentHolder>(relaxed = true)
        val second = mockk<ComponentHolder>(relaxed = true)
        val holders = ArrayDeque(listOf(first, second))
        val resources = DaemonComponents(factory = { holders.removeFirst() }, targetGuard = guard)
        val context = CliExecutionContext.local()
        fun request() = CliRequestExecutor().execute(arrayOf("get_device_info", "--platform", "android"),
            context, acquireComponents = { resources.acquire(context, it) })
        request()
        request()
        assertEquals(1, resources.initializationCount)
        target = "second"
        request()
        assertEquals(2, resources.initializationCount)
        assertEquals(3, inventories)
        verify(exactly = 1) { first.close() }
        resources.close()
    }

    @Test fun `disconnected inventory invalidates retained graph`() {
        var targets = listOf("device")
        val holder = mockk<ComponentHolder>(relaxed = true)
        val resources = DaemonComponents(factory = { holder }, targetGuard = DaemonTargetGuard(
            androidInventory = { targets }, androidForwards = { "" }))
        val context = CliExecutionContext.local()
        fun request() = CliRequestExecutor().execute(arrayOf("get_device_info", "--platform", "android"),
            context, acquireComponents = { resources.acquire(context, it) })
        request()
        targets = emptyList()
        assertEquals(4, request().exitCode)
        verify(exactly = 1) { holder.close() }
        resources.close()
        verify(exactly = 1) { holder.close() }
    }

    @Test fun `flow recording admits target before recorder allocation after preflight`() {
        var acquisitions = 0
        val context = CliExecutionContext(environment = emptyMap())
        val provider: (dev.visiontest.performance.TraceRuntime) -> ComponentHolder = {
            acquisitions++
            throw dev.visiontest.cli.CliExit(dev.visiontest.cli.ExitCode.DeviceNotFound, "admission rejected")
        }
        val invalid = CliRequestExecutor().execute(arrayOf("flow", "--platform", "android", "--record",
            "--step", "not_a_command"), context, acquireComponents = provider)
        assertEquals(2, invalid.exitCode)
        assertEquals(0, acquisitions)
        val valid = CliRequestExecutor().execute(arrayOf("flow", "--platform", "android", "--record",
            "--step", "press_home", "--json"), context, acquireComponents = provider)
        assertEquals(1, acquisitions)
        assertEquals(4, valid.exitCode)
        kotlin.test.assertContains(valid.stderr!!, "admission rejected")
    }

    @Test fun `admission observes nested flow startup and step deadlines`() {
        for (record in listOf(false, true)) {
            val resources = DaemonComponents(factory = { mockk(relaxed = true) }, targetGuard = DaemonTargetGuard(
                androidInventory = { kotlinx.coroutines.delay(1000); listOf("serial") }, androidForwards = { "" }))
            val context = CliExecutionContext.local()
            val args = listOf("flow", "--platform", "android", "--step", "press_home", "--timeout", "50", "--json") +
                if (record) listOf("--record") else emptyList()
            val result = CliRequestExecutor().execute(args.toTypedArray(), context,
                acquireComponents = { resources.acquire(context, it) })
            assertEquals(1, result.exitCode)
            assertEquals(0, resources.initializationCount)
            resources.close()
        }
    }

    @Test fun `invalid request never reaches daemon provider`() {
        var acquisitions = 0
        val result = CliRequestExecutor().execute(arrayOf("tap_by_coordinates", "--platform", "android", "-1", "2"),
            CliExecutionContext.local(), acquireComponents = { acquisitions++; error("invalid request admitted") })
        assertEquals(2, result.exitCode)
        assertEquals(0, acquisitions)
    }
}
