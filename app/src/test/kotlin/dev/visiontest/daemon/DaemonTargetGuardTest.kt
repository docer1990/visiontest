package dev.visiontest.daemon

import dev.visiontest.cli.CliExit
import dev.visiontest.cli.Platform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DaemonTargetGuardTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: java.nio.file.Path

    @Test fun `production inventory resolves caller SDK adb without PATH`() = runTest {
        val platformTools = java.nio.file.Files.createDirectories(directory.resolve("platform-tools"))
        val adb = platformTools.resolve("adb")
        java.nio.file.Files.writeString(adb, "#!/bin/sh\nif [ \"\$5\" = devices ]; then\n" +
            "  printf 'List of devices attached\\nserial\\tdevice\\n'\nfi\n")
        check(adb.toFile().setExecutable(true))
        val context = dev.visiontest.cli.CliExecutionContext(
            environment = mapOf("ANDROID_HOME" to directory.toString()))
        kotlinx.coroutines.withContext(context.coroutineContext()) {
            assertEquals("serial", DaemonTargetGuard().check(Platform.Android, "press_home")?.id)
        }
    }

    @Test fun `production inventory cancellation settles its owned child promptly`() = runTest {
        val platformTools = java.nio.file.Files.createDirectories(directory.resolve("platform-tools"))
        val adb = platformTools.resolve("adb")
        java.nio.file.Files.writeString(adb, "#!/bin/sh\nexec /bin/sleep 30\n")
        check(adb.toFile().setExecutable(true))
        val context = dev.visiontest.cli.CliExecutionContext(
            environment = mapOf("ANDROID_HOME" to directory.toString()))
        val start = System.nanoTime()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default + context.coroutineContext()) {
            assertNull(kotlinx.coroutines.withTimeoutOrNull(100) {
                DaemonTargetGuard().check(Platform.Android, "press_home")
            })
        }
        kotlin.test.assertTrue((System.nanoTime() - start) / 1_000_000 < 2000, "Inventory outlived caller deadline")
    }

    @Test fun `daemon JVM ADB port override is rejected before inventory`() = runTest {
        val name = "ANDROID_ADB_SERVER_PORT"
        val previous = System.getProperty(name)
        var inventories = 0
        try {
            System.setProperty(name, "5038")
            val guard = DaemonTargetGuard(androidInventory = { inventories++; listOf("serial") },
                androidForwards = { "" }, environment = { emptyMap() })
            val error = assertFailsWith<CliExit> { guard.check(Platform.Android, "press_home") }
            kotlin.test.assertContains(error.message, "--standalone")
            assertEquals(0, inventories)
            System.setProperty(name, "5037")
            assertEquals("serial", guard.check(Platform.Android, "press_home")?.id)
        } finally {
            if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
        }
    }

    @Test fun `unsupported ADB server routing is rejected before inventory`() = runTest {
        for ((name, value) in listOf("ADB_SERVER_SOCKET" to "tcp:remote:5037",
            "ANDROID_ADB_SERVER_PORT" to "5038", "ANDROID_ADB_SERVER_ADDRESS" to "remote",
            "ADB_SERVER_HOST" to "remote")) {
            var inventories = 0
            val guard = DaemonTargetGuard(androidInventory = { inventories++; listOf("serial") },
                environment = { mapOf(name to value) })
            val error = assertFailsWith<CliExit> { guard.check(Platform.Android, "press_home") }
            assertEquals(1, error.code.value)
            kotlin.test.assertContains(error.message, "--standalone")
            assertEquals(0, inventories)
        }
    }

    @Test fun `default-equivalent ADB routing and matching caller serial are admitted`() = runTest {
        val guard = DaemonTargetGuard(androidInventory = { listOf("serial") }, androidForwards = { "" },
            environment = { mapOf("ADB_SERVER_SOCKET" to "tcp:localhost:5037",
                "ANDROID_ADB_SERVER_PORT" to "5037", "ANDROID_ADB_SERVER_ADDRESS" to "127.0.0.1",
                "ANDROID_SERIAL" to "serial") })
        assertEquals("serial", guard.check(Platform.Android, "press_home")?.id)
    }

    @Test fun `caller serial must match the sole admitted device`() = runTest {
        var forwards = 0
        val guard = DaemonTargetGuard(androidInventory = { listOf("serial") }, androidForwards = { forwards++; "" },
            environment = { mapOf("ANDROID_SERIAL" to "other") })
        val error = assertFailsWith<CliExit> { guard.check(Platform.Android, "press_home") }
        assertEquals(1, error.code.value)
        kotlin.test.assertContains(error.message, "ANDROID_SERIAL")
        kotlin.test.assertContains(error.message, "--standalone")
        assertEquals(0, forwards)
    }

    @Test fun `fresh android inventory requires exactly one usable serial`() = runTest {
        var devices = emptyList<String>()
        val guard = DaemonTargetGuard(androidInventory = { devices }, androidForwards = { "" },
            iosInventory = { emptyList() })
        assertEquals(4, assertFailsWith<CliExit> { guard.check(Platform.Android, "press_home") }.code.value)
        devices = listOf("first")
        assertEquals("first", guard.check(Platform.Android, "press_home")?.id)
        devices = listOf("second")
        assertEquals("second", guard.check(Platform.Android, "press_home")?.id)
        devices = listOf("first", "second")
        assertEquals(1, assertFailsWith<CliExit> { guard.check(Platform.Android, "press_home") }.code.value)
    }

    @Test fun `fixed android forward must belong to admitted serial`() = runTest {
        var forwards = "other tcp:9008 tcp:9008"
        val guard = DaemonTargetGuard(androidInventory = { listOf("target") }, androidForwards = { forwards })
        assertEquals(1, assertFailsWith<CliExit> { guard.check(Platform.Android, "press_home") }.code.value)
        forwards = "other tcp:9010 tcp:9008\ntarget tcp:9008 tcp:9008"
        assertEquals("target", guard.check(Platform.Android, "press_home")?.id)
    }

    @Test fun `ios admission requires exactly one booted simulator`() = runTest {
        var devices = emptyList<String>()
        val guard = DaemonTargetGuard(iosInventory = { devices })
        assertEquals(4, assertFailsWith<CliExit> { guard.check(Platform.Ios, "press_home") }.code.value)
        devices = listOf("booted")
        assertEquals("booted", guard.check(Platform.Ios, "press_home")?.id)
        devices = listOf("booted", "also-booted")
        assertEquals(1, assertFailsWith<CliExit> { guard.check(Platform.Ios, "press_home") }.code.value)
    }

    @Test fun `diagnostic inspection status and owned ios stop need no target`() = runTest {
        val guard = DaemonTargetGuard(androidInventory = { error("must not inspect") },
            androidForwards = { error("must not inspect") }, iosInventory = { error("must not inspect") })
        for (platform in Platform.entries) {
            assertNull(guard.check(platform, "available_device"))
            assertNull(guard.check(platform, "automation_server_status"))
        }
        assertNull(guard.check(Platform.Ios, "stop_automation_server"))
    }
}
