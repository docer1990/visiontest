package com.example.visiontest.cli

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.common.automationResult
import com.example.visiontest.common.FieldType
import com.example.visiontest.common.validateElement
import com.example.visiontest.common.validateFields
import com.example.visiontest.common.validateFindElementResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun inspectionOutput(
    response: String,
    json: Boolean,
    command: InspectionCommand,
    platform: Platform
): String {
    return if (json) unwrapInspectionResult(response, command, platform) else response
}

private fun unwrapInspectionResult(response: String, command: InspectionCommand, platform: Platform): String {
    val responseValue = automationResult(response)
    val result = responseValue.value
    if (!responseValue.isRpcError) {
        validateInspectionResult(result, command, platform)
    }
    return result.toString()
}

private fun validateInspectionResult(result: JsonObject, command: InspectionCommand, platform: Platform) {
    when (command) {
        InspectionCommand.DEVICE_INFO -> {
            result.validateFields("success", FieldType.BOOLEAN, required = true)
            result.validateFields("displayWidth displayHeight displayRotation", FieldType.INTEGER, required = true)
            result.validateFields("productName", FieldType.STRING, required = true)
            if (platform == Platform.Android) result.validateFields("sdkVersion", FieldType.INTEGER, required = true)
            else result.validateFields("osVersion", FieldType.STRING, required = true)
        }
        InspectionCommand.FIND_ELEMENT -> {
            validateFindElementResult(result, platform == Platform.Ios)
        }
        InspectionCommand.INTERACTIVE_ELEMENTS -> {
            result.validateFields("success", FieldType.BOOLEAN, required = true)
            result.validateFields("count", FieldType.INTEGER, required = true)
            val elements = result["elements"] as? JsonArray
                ?: throw CommandExecutionException("Automation result.elements must be an array")
            for (element in elements) {
                val objectElement = element as? JsonObject
                    ?: throw CommandExecutionException("Automation result.elements entries must be objects")
                validateElement(objectElement, platform == Platform.Ios, interactive = true)
            }
        }
    }
    result.validateFields("error", FieldType.STRING)
}
