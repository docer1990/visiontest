package com.example.visiontest.ios

import com.example.visiontest.AppNotFoundException
import com.example.visiontest.IOSSimulatorException
import com.example.visiontest.NoSimulatorAvailableException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IOSSimulatorTest {

    private val mockExecutor = mockk<ProcessExecutor>()
    private val mockBootWaitExecutor = mockk<ProcessExecutor>()
    private val simulator = IOSSimulator(
        processExecutor = mockExecutor,
        bootWaitExecutor = mockBootWaitExecutor,
    )

    // Helper to build a simctl JSON with one booted device
    private val bootedDeviceJson = """
    {
      "devices": {
        "com.apple.CoreSimulator.SimRuntime.iOS-17-2": [
          {
            "udid": "BOOTED-ID",
            "name": "iPhone 15",
            "state": "Booted",
            "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15"
          }
        ]
      }
    }
    """.trimIndent()

    private val shutdownDeviceJson = """
    {
      "devices": {
        "com.apple.CoreSimulator.SimRuntime.iOS-17-2": [
          {
            "udid": "SHUTDOWN-ID",
            "name": "iPhone 15",
            "state": "Shutdown",
            "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15"
          }
        ]
      }
    }
    """.trimIndent()

    private val emptyDevicesJson = """{"devices": {}}"""

    private fun commandResult(exitCode: Int = 0, output: String = "", errorOutput: String = "") =
        ProcessExecutor.CommandResult(exitCode, output, errorOutput)

    // --- listDevices ---

    @Test
    fun `listDevices delegates to parseDeviceList when exitCode is 0`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        val devices = simulator.listDevices()
        assertEquals(1, devices.size)
        assertEquals("BOOTED-ID", devices[0].id)
        assertEquals("iPhone 15", devices[0].name)
    }

    @Test
    fun `listDevices throws IOSSimulatorException on non-zero exit`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(exitCode = 1, errorOutput = "simctl failed")

        val ex = assertFailsWith<IOSSimulatorException> {
            simulator.listDevices()
        }
        assertTrue(ex.message!!.contains("simctl failed"))
    }

    // --- getFirstAvailableDevice ---

    @Test
    fun `getFirstAvailableDevice returns booted device first`() = runTest {
        val json = """
        {
          "devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-17-2": [
              {
                "udid": "SHUTDOWN-ID",
                "name": "iPhone 14",
                "state": "Shutdown",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-14"
              },
              {
                "udid": "BOOTED-ID",
                "name": "iPhone 15",
                "state": "Booted",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15"
              }
            ]
          }
        }
        """.trimIndent()

        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = json)

        val device = simulator.getFirstAvailableDevice()
        assertEquals("BOOTED-ID", device.id)
        assertEquals("Booted", device.state)
    }

    @Test
    fun `getFirstAvailableDevice returns shutdown device if no booted`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = shutdownDeviceJson)

        val device = simulator.getFirstAvailableDevice()
        assertEquals("SHUTDOWN-ID", device.id)
        assertEquals("Shutdown", device.state)
    }

    @Test
    fun `getFirstAvailableDevice throws when no devices available`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = emptyDevicesJson)

        assertFailsWith<NoSimulatorAvailableException> {
            simulator.getFirstAvailableDevice()
        }
    }

    // --- listApps ---

    @Test
    fun `listApps calls simctl listapps and parses result`() = runTest {
        // First call: listDevices for getDevice -> getFirstAvailableDevice
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        // listapps call
        val plistOutput = """"com.apple.mobilesafari" = { name = Safari; };"""
        coEvery { mockExecutor.execute("xcrun", "simctl", "listapps", "BOOTED-ID") } returns
            commandResult(output = plistOutput)

        val apps = simulator.listApps(null)
        assertEquals(1, apps.size)
        assertEquals("com.apple.mobilesafari", apps[0])
    }

    @Test
    fun `listApps throws on failure`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "listapps", "BOOTED-ID") } returns
            commandResult(exitCode = 1, errorOutput = "listapps failed")

        val ex = assertFailsWith<IOSSimulatorException> {
            simulator.listApps(null)
        }
        assertTrue(ex.message!!.contains("listapps failed"))
    }

    // --- getAppInfo ---

    @Test
    fun `getAppInfo rejects invalid bundleId`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            simulator.getAppInfo("invalid", null)
        }
    }

    @Test
    fun `getAppInfo throws AppNotFoundException on non-zero exit`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "get_app_container", "BOOTED-ID", "com.example.app") } returns
            commandResult(exitCode = 1, errorOutput = "not found")

        assertFailsWith<AppNotFoundException> {
            simulator.getAppInfo("com.example.app", null)
        }
    }

    @Test
    fun `getAppInfo returns info on success`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "get_app_container", "BOOTED-ID", "com.example.app") } returns
            commandResult(output = "/path/to/container")

        val info = simulator.getAppInfo("com.example.app", null)
        assertTrue(info.contains("com.example.app"))
        assertTrue(info.contains("/path/to/container"))
    }

    // --- launchApp ---

    @Test
    fun `launchApp rejects invalid bundleId`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            simulator.launchApp("invalid", null, null)
        }
    }

    @Test
    fun `launchApp returns true on success`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "launch", "BOOTED-ID", "com.example.app") } returns
            commandResult(output = "com.example.app: 12345")

        val result = simulator.launchApp("com.example.app", null, null)
        assertTrue(result)
    }

    @Test
    fun `launchApp throws on failure`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "launch", "BOOTED-ID", "com.example.app") } returns
            commandResult(exitCode = 1, errorOutput = "launch failed")

        assertFailsWith<IOSSimulatorException> {
            simulator.launchApp("com.example.app", null, null)
        }
    }

    // --- executeShell ---

    @Test
    fun `executeShell is not supported on iOS`() = runTest {
        assertFailsWith<UnsupportedOperationException> {
            simulator.executeShell("ls -la", null)
        }
    }

    // --- ensureDeviceBooted (indirectly via operations on shutdown device) ---

    @Test
    fun `operations on shutdown device trigger boot and wait for bootstatus`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = shutdownDeviceJson)

        // Boot call
        coEvery { mockExecutor.execute("xcrun", "simctl", "boot", "SHUTDOWN-ID") } returns
            commandResult()

        // Boot wait call
        coEvery { mockBootWaitExecutor.execute("xcrun", "simctl", "bootstatus", "SHUTDOWN-ID", "-b") } returns
            commandResult()

        // Launch call
        coEvery { mockExecutor.execute("xcrun", "simctl", "launch", "SHUTDOWN-ID", "com.example.app") } returns
            commandResult()

        val result = simulator.launchApp("com.example.app", null, null)
        assertTrue(result)

        coVerify(exactly = 1) { mockBootWaitExecutor.execute("xcrun", "simctl", "bootstatus", "SHUTDOWN-ID", "-b") }
    }

    @Test
    fun `operations on shutdown device throw if bootstatus fails`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = shutdownDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "boot", "SHUTDOWN-ID") } returns
            commandResult()

        coEvery { mockBootWaitExecutor.execute("xcrun", "simctl", "bootstatus", "SHUTDOWN-ID", "-b") } returns
            commandResult(exitCode = 1, errorOutput = "boot never completed")

        assertFailsWith<IOSSimulatorException> {
            simulator.launchApp("com.example.app", null, null)
        }
    }

    @Test
    fun `operations on shutdown device throw if bootstatus times out`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = shutdownDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "boot", "SHUTDOWN-ID") } returns
            commandResult()

        coEvery { mockBootWaitExecutor.execute("xcrun", "simctl", "bootstatus", "SHUTDOWN-ID", "-b") } throws
            CommandTimeoutException("Command timed out")

        assertFailsWith<IOSSimulatorException> {
            simulator.launchApp("com.example.app", null, null)
        }
    }

    @Test
    fun `operations on shutdown device throw if boot fails`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = shutdownDeviceJson)

        coEvery { mockExecutor.execute("xcrun", "simctl", "boot", "SHUTDOWN-ID") } returns
            commandResult(exitCode = 1, errorOutput = "boot failed")

        assertFailsWith<IOSSimulatorException> {
            simulator.launchApp("com.example.app", null, null)
        }
    }

    @Test
    fun `operations on booted device skip boot`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        // No boot call expected — goes straight to launch
        coEvery { mockExecutor.execute("xcrun", "simctl", "launch", "BOOTED-ID", "com.example.app") } returns
            commandResult()

        val result = simulator.launchApp("com.example.app", null, null)
        assertTrue(result)

        coVerify(exactly = 0) { mockExecutor.execute("xcrun", "simctl", "boot", any()) }
    }

    // --- getDevice with specific deviceId ---

    @Test
    fun `operations with specific deviceId find the correct device`() = runTest {
        val json = """
        {
          "devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-17-2": [
              {
                "udid": "DEVICE-A",
                "name": "iPhone 14",
                "state": "Booted",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-14"
              },
              {
                "udid": "DEVICE-B",
                "name": "iPhone 15",
                "state": "Booted",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15"
              }
            ]
          }
        }
        """.trimIndent()

        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = json)

        coEvery { mockExecutor.execute("xcrun", "simctl", "launch", "DEVICE-B", "com.example.app") } returns
            commandResult()

        val result = simulator.launchApp("com.example.app", null, "DEVICE-B")
        assertTrue(result)
    }

    @Test
    fun `operations with unknown deviceId throw NoSimulatorAvailableException`() = runTest {
        coEvery { mockExecutor.execute("xcrun", "simctl", "list", "devices", "available", "--json") } returns
            commandResult(output = bootedDeviceJson)

        assertFailsWith<NoSimulatorAvailableException> {
            simulator.launchApp("com.example.app", null, "NONEXISTENT-ID")
        }
    }
}
