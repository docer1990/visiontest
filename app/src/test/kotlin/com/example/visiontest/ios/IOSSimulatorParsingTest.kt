package com.example.visiontest.ios

import com.example.visiontest.common.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IOSSimulatorParsingTest {

    private val simulator = IOSSimulator()

    // --- parseDeviceList ---

    @Test
    fun `parseDeviceList with empty devices map returns empty list`() {
        val json = """{"devices": {}}"""
        val result = simulator.parseDeviceList(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseDeviceList parses single booted simulator`() {
        val json = """
        {
          "devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-17-2": [
              {
                "udid": "ABC-123",
                "name": "iPhone 15",
                "state": "Booted",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15"
              }
            ]
          }
        }
        """.trimIndent()

        val result = simulator.parseDeviceList(json)
        assertEquals(1, result.size)

        val device = result[0]
        assertEquals("ABC-123", device.id)
        assertEquals("iPhone 15", device.name)
        assertEquals("Booted", device.state)
        assertEquals(DeviceType.IOS_SIMULATOR, device.type)
        assertEquals("17.2", device.osVersion)
        assertEquals("iPhone-15", device.modelName)
    }

    @Test
    fun `parseDeviceList extracts OS version from runtime key`() {
        val json = """
        {
          "devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-17-2": [
              {
                "udid": "ID-1",
                "name": "Test",
                "state": "Shutdown",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15"
              }
            ]
          }
        }
        """.trimIndent()

        val result = simulator.parseDeviceList(json)
        assertEquals("17.2", result[0].osVersion)
    }

    @Test
    fun `parseDeviceList handles multiple runtimes with multiple devices`() {
        val json = """
        {
          "devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-17-2": [
              {
                "udid": "ID-1",
                "name": "iPhone 15",
                "state": "Booted",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15"
              },
              {
                "udid": "ID-2",
                "name": "iPhone 15 Pro",
                "state": "Shutdown",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-15-Pro"
              }
            ],
            "com.apple.CoreSimulator.SimRuntime.iOS-16-4": [
              {
                "udid": "ID-3",
                "name": "iPhone 14",
                "state": "Shutdown",
                "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-14"
              }
            ]
          }
        }
        """.trimIndent()

        val result = simulator.parseDeviceList(json)
        assertEquals(3, result.size)
        assertTrue(result.any { it.osVersion == "17.2" })
        assertTrue(result.any { it.osVersion == "16.4" })
    }

    @Test
    fun `parseDeviceList with malformed JSON returns empty list`() {
        val result = try {
            simulator.parseDeviceList("not valid json")
        } catch (_: Exception) {
            emptyList()
        }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseDeviceList with missing devices key returns empty list`() {
        val json = """{"other": "data"}"""
        val result = simulator.parseDeviceList(json)
        assertTrue(result.isEmpty())
    }

    // --- parseAppListFromPlist ---

    @Test
    fun `parseAppListFromPlist with empty string returns empty list`() {
        val result = simulator.parseAppListFromPlist("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseAppListFromPlist extracts single bundle ID`() {
        val plist = """"com.apple.mobilesafari" = { CFBundleDisplayName = Safari; };"""
        val result = simulator.parseAppListFromPlist(plist)
        assertEquals(listOf("com.apple.mobilesafari"), result)
    }

    @Test
    fun `parseAppListFromPlist extracts multiple bundle IDs`() {
        val plist = """
            "com.apple.mobilesafari" = { name = Safari; };
            "com.apple.Preferences" = { name = Settings; };
            "com.example.testapp" = { name = Test; };
        """.trimIndent()
        val result = simulator.parseAppListFromPlist(plist)
        assertEquals(3, result.size)
        assertTrue(result.contains("com.apple.mobilesafari"))
        assertTrue(result.contains("com.apple.Preferences"))
        assertTrue(result.contains("com.example.testapp"))
    }

    @Test
    fun `parseAppListFromPlist returns distinct results`() {
        val plist = """
            "com.apple.mobilesafari" = { };
            "com.apple.mobilesafari" = { };
        """.trimIndent()
        val result = simulator.parseAppListFromPlist(plist)
        assertEquals(1, result.size)
    }

    // --- isValidBundleId ---

    @Test
    fun `isValidBundleId accepts valid bundle IDs`() {
        assertTrue(simulator.isValidBundleId("com.example.app"))
        assertTrue(simulator.isValidBundleId("com.example.my-app"))
        assertTrue(simulator.isValidBundleId("com.example.my_app"))
        assertTrue(simulator.isValidBundleId("com.apple.mobilesafari"))
        assertTrue(simulator.isValidBundleId("_com.example.app"))
    }

    @Test
    fun `isValidBundleId rejects blank string`() {
        assertFalse(simulator.isValidBundleId(""))
        assertFalse(simulator.isValidBundleId("   "))
    }

    @Test
    fun `isValidBundleId rejects single segment`() {
        assertFalse(simulator.isValidBundleId("myapp"))
    }

    @Test
    fun `isValidBundleId rejects segment starting with digit`() {
        assertFalse(simulator.isValidBundleId("com.1example.app"))
    }

    @Test
    fun `isValidBundleId rejects shell metacharacters`() {
        assertFalse(simulator.isValidBundleId("com.example.app;rm -rf"))
    }

    @Test
    fun `isValidBundleId rejects consecutive dots`() {
        assertFalse(simulator.isValidBundleId("com..example.app"))
    }

    @Test
    fun `isValidBundleId rejects trailing dot`() {
        assertFalse(simulator.isValidBundleId("com.example.app."))
    }

    // --- isValidShellCommand ---

    @Test
    fun `isValidShellCommand accepts valid commands`() {
        assertTrue(simulator.isValidShellCommand("ls -la"))
        assertTrue(simulator.isValidShellCommand("echo hello"))
        assertTrue(simulator.isValidShellCommand("cat /tmp/file.txt"))
    }

    @Test
    fun `isValidShellCommand rejects blank`() {
        assertFalse(simulator.isValidShellCommand(""))
        assertFalse(simulator.isValidShellCommand("   "))
    }

    @Test
    fun `isValidShellCommand rejects exceeding max length`() {
        val longCommand = "a".repeat(1001)
        assertFalse(simulator.isValidShellCommand(longCommand))
    }

    @Test
    fun `isValidShellCommand rejects semicolon`() {
        assertFalse(simulator.isValidShellCommand("ls; rm -rf /"))
    }

    @Test
    fun `isValidShellCommand rejects pipe`() {
        assertFalse(simulator.isValidShellCommand("ls | grep foo"))
    }

    @Test
    fun `isValidShellCommand rejects dollar sign`() {
        assertFalse(simulator.isValidShellCommand("echo \$HOME"))
    }

    @Test
    fun `isValidShellCommand rejects backtick`() {
        assertFalse(simulator.isValidShellCommand("echo `whoami`"))
    }

    @Test
    fun `isValidShellCommand rejects newline`() {
        assertFalse(simulator.isValidShellCommand("ls\nrm -rf /"))
    }
}
