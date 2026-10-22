package com.example.visiontest.android

import com.example.visiontest.common.MobileDevice
import com.example.visiontest.common.DeviceType
import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.recording.RecordingException

internal fun parseAdbDevices(output: String): List<MobileDevice> {
    val lines = output.lineSequence().toList()
    if (lines.firstOrNull() != "List of devices attached") malformedDevices("header")
    val serials = mutableSetOf<String>()
    val devices = lines.drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
        val match = Regex("([^\\s]+)\\t" +
            "(device|offline|unauthorized|bootloader|recovery|sideload|host|connecting|no permissions)")
            .matchEntire(line) ?: malformedDevices("entry")
        val serial = match.groupValues[1]
        if (!serials.add(serial)) malformedDevices("duplicate serial")
        if (match.groupValues[2] == "device") MobileDevice(serial, serial, DeviceType.ANDROID, "DEVICE") else null
    }
    if (devices.isEmpty()) throw NoDeviceAvailableException("no Android devices available")
    return devices
}

private fun malformedDevices(detail: String): Nothing = throw RecordingException("Malformed adb devices $detail")
