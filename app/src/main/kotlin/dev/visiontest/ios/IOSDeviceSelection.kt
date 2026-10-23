package dev.visiontest.ios

import dev.visiontest.common.MobileDevice
import dev.visiontest.common.DeviceType
import dev.visiontest.NoSimulatorAvailableException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseIosSimulatorDevices(jsonOutput: String): List<MobileDevice> {
    val devices = mutableListOf<MobileDevice>()
    val jsonElement = Json.parseToJsonElement(jsonOutput)
    val devicesMap = jsonElement.jsonObject["devices"]?.jsonObject ?: return emptyList()

    for ((runtime, deviceArray) in devicesMap) {
        if (!runtime.contains(".iOS-")) continue
        val osVersion = runtime.substringAfterLast("iOS-").replace("-", ".")
        deviceArray.jsonArray.forEach { deviceJson ->
            val deviceObj = deviceJson.jsonObject
            devices.add(
                MobileDevice(
                    id = deviceObj["udid"]?.jsonPrimitive?.content ?: "",
                    name = deviceObj["name"]?.jsonPrimitive?.content ?: "Unknown",
                    type = DeviceType.IOS_SIMULATOR,
                    osVersion = osVersion,
                    state = deviceObj["state"]?.jsonPrimitive?.content ?: "Unknown",
                    modelName = deviceObj["deviceTypeIdentifier"]?.jsonPrimitive?.content
                        ?.substringAfterLast(".") ?: "Unknown"
                )
            )
        }
    }
    return devices
}

internal fun selectFirstAvailableSimulator(devices: List<MobileDevice>): MobileDevice =
    devices.firstOrNull { it.state == "Booted" }
        ?: devices.firstOrNull { it.state == "Shutdown" }
        ?: throw NoSimulatorAvailableException("No iOS simulator available")
