package com.example.visiontest.android

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.common.DeviceType
import com.example.visiontest.recording.RecordingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidDeviceSelectionTest {
    @Test
    fun `permission diagnostics do not block an online device`() {
        val devices = parseAdbDevices("List of devices attached\n" +
            "usb-1\tno permissions (user in plugdev group; are your udev rules wrong?); see [http://developer.android.com/tools/device.html]\n" +
            "emulator-5554\tdevice\n")
        assertEquals(listOf("emulator-5554"), devices.map { it.id })
    }

    @Test
    fun `permission diagnostics alone retain missing device classification`() {
        assertFailsWith<NoDeviceAvailableException> {
            parseAdbDevices("List of devices attached\n" +
                "usb-1\tno permissions (missing udev rules? user is in the plugdev group)\n")
        }
    }

    @Test
    fun `offline devices cannot become the recording target`() {
        val devices = parseAdbDevices(
            "List of devices attached\nserial-offline\toffline\nserial-1\tdevice\nserial-2\tdevice\n"
        )
        assertEquals("serial-1", devices.first().id)
        assertEquals(listOf("serial-1", "serial-2"), devices.map { it.id })
        devices.forEach {
            assertEquals("DEVICE", it.state)
            assertEquals(DeviceType.ANDROID, it.type)
            assertEquals(it.id, it.name)
        }
    }

    @Test
    fun `valid empty lists retain missing device classification`() {
        listOf("List of devices attached\n\n", "List of devices attached\r\na\tunauthorized\r\nb\toffline\r\n")
            .forEach { assertFailsWith<NoDeviceAvailableException> { parseAdbDevices(it) } }
    }

    @Test
    fun `malformed discovery never selects a partial target`() {
        listOf("", "serial-1\tdevice", "List of devices attached\na device\n",
            "List of devices attached\na\tunknown\n", "List of devices attached\n\tdevice\n",
            "List of devices attached\na\tdevice extra\n", "List of devices attached\na\tdevice\nbad\n",
            "List of devices attached\na\tunauthorized extra\n", "List of devices attached\na\tno permissionsINVALID\n",
            "List of devices attached\na\tdevice\na\tdevice\n").forEach {
            assertFailsWith<RecordingException> { parseAdbDevices(it) }
        }
    }
}
