package com.example.visiontest.cli

import com.example.visiontest.CommandExecutionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

internal fun inspectionOutput(
    response: String,
    json: Boolean,
    command: InspectionCommand,
    platform: Platform
): String {
    return if (json) unwrapInspectionResult(response, command, platform) else response
}

private fun unwrapInspectionResult(response: String, command: InspectionCommand, platform: Platform): String {
    val body = parseResponseObject(response)
    val error = body["error"]?.takeUnless { it == JsonNull }
    return if (error != null) formatRpcError(body, error) else {
        val result = body["result"] as? JsonObject
            ?: throw CommandExecutionException("Automation response has no result object")
        validateInspectionResult(result, command, platform)
        result.toString()
    }
}

private fun parseResponseObject(response: String): JsonObject {
    return try {
        Json.parseToJsonElement(response) as? JsonObject
            ?: throw CommandExecutionException("Automation response must be a JSON object")
    } catch (e: IllegalArgumentException) {
        throw CommandExecutionException("Malformed automation response: ${e.message}").also {
            it.initCause(e)
        }
    }
}

private fun formatRpcError(body: JsonObject, error: JsonElement): String {
    val objectError = error as? JsonObject
    val code = objectError?.get("code") as? JsonPrimitive
    val message = objectError?.get("message") as? JsonPrimitive
    val valid = !body.containsKey("result") && code?.intOrNull != null && !code.isString && message?.isString == true
    if (!valid) throw CommandExecutionException("Invalid automation error response")
    return buildJsonObject { put("error", error) }.toString()
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
            result.validateFields("found", FieldType.BOOLEAN, required = true)
            validateElement(result, platform, interactive = false)
        }
        InspectionCommand.INTERACTIVE_ELEMENTS -> {
            result.validateFields("success", FieldType.BOOLEAN, required = true)
            result.validateFields("count", FieldType.INTEGER, required = true)
            val elements = result["elements"] as? JsonArray
                ?: throw CommandExecutionException("Automation result.elements must be an array")
            for (element in elements) {
                val objectElement = element as? JsonObject
                    ?: throw CommandExecutionException("Automation result.elements entries must be objects")
                validateElement(objectElement, platform, interactive = true)
            }
        }
    }
    result.validateFields("error", FieldType.STRING)
}

private fun validateElement(element: JsonObject, platform: Platform, interactive: Boolean) {
    element.validateFields("text resourceId className contentDescription bounds", FieldType.STRING)
    element.validateFields("isEnabled", FieldType.BOOLEAN)
    if (platform == Platform.Android) element.validateFields("isClickable", FieldType.BOOLEAN)
    else element.validateFields("value", FieldType.STRING)
    if (interactive) {
        element.validateFields("centerX centerY", FieldType.INTEGER)
        if (platform == Platform.Android) {
            element.validateFields("isCheckable isScrollable isLongClickable", FieldType.BOOLEAN)
        }
    }
}

private enum class FieldType {
    STRING, BOOLEAN, INTEGER;

    fun accepts(value: JsonElement?): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        return when (this) {
            STRING -> primitive.isString
            BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null
            INTEGER -> !primitive.isString && primitive.intOrNull != null
        }
    }
}

private fun JsonObject.validateFields(fields: String, type: FieldType, required: Boolean = false) {
    for (field in fields.split(" ")) {
        if ((required || containsKey(field)) && !type.accepts(get(field))) {
            throw CommandExecutionException("Automation result.$field must be ${type.name.lowercase()}")
        }
    }
}
