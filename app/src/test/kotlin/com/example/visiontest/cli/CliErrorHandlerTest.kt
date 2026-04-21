package com.example.visiontest.cli

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.NoSimulatorAvailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CliErrorHandlerTest {

    @Test
    fun `success returns exit 0 with stdout`() {
        val result = executeCliCommand { "hello world" }
        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.stdout)
        assertNull(result.stderr)
    }

    @Test
    fun `CliExit ServerNotReachable returns exit 3`() {
        val result = executeCliCommand {
            throw CliExit(ExitCode.ServerNotReachable, "Server not running")
        }
        assertEquals(3, result.exitCode)
        assertNull(result.stdout)
        assertEquals("Server not running", result.stderr)
    }

    @Test
    fun `CliExit DeviceNotFound returns exit 4`() {
        val result = executeCliCommand {
            throw CliExit(ExitCode.DeviceNotFound, "No device")
        }
        assertEquals(4, result.exitCode)
        assertNull(result.stdout)
    }

    @Test
    fun `CliExit PlatformNotSupported returns exit 5`() {
        val result = executeCliCommand {
            throw CliExit(ExitCode.PlatformNotSupported, "Android only")
        }
        assertEquals(5, result.exitCode)
    }

    @Test
    fun `NoDeviceAvailableException returns exit 4`() {
        val result = executeCliCommand {
            throw NoDeviceAvailableException("No Android device found")
        }
        assertEquals(4, result.exitCode)
        assertEquals("No Android device found", result.stderr)
    }

    @Test
    fun `NoSimulatorAvailableException returns exit 4`() {
        val result = executeCliCommand {
            throw NoSimulatorAvailableException("No iOS simulator found")
        }
        assertEquals(4, result.exitCode)
        assertEquals("No iOS simulator found", result.stderr)
    }

    @Test
    fun `IllegalArgumentException returns exit 2`() {
        val result = executeCliCommand {
            throw IllegalArgumentException("bad arg")
        }
        assertEquals(2, result.exitCode)
        assertEquals("bad arg", result.stderr)
    }

    @Test
    fun `unexpected exception returns exit 1`() {
        val result = executeCliCommand {
            throw RuntimeException("something broke")
        }
        assertEquals(1, result.exitCode)
        assertEquals("something broke", result.stderr)
    }

    @Test
    fun `CliExit GenericFailure returns exit 1`() {
        val result = executeCliCommand {
            throw CliExit(ExitCode.GenericFailure, "generic error")
        }
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `CliExit UsageError returns exit 2`() {
        val result = executeCliCommand {
            throw CliExit(ExitCode.UsageError, "usage problem")
        }
        assertEquals(2, result.exitCode)
    }
}
