package dev.visiontest.common

import dev.visiontest.CommandExecutionException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal fun validateFindElementResponse(response: String, ios: Boolean) {
    val parsed = automationResult(response)
    if (!parsed.isRpcError) {
        validateFindElementResult(parsed.value, ios)
        val error = parsed.value["error"] as? JsonPrimitive
        if (error != null) throw CommandExecutionException(error.content)
    }
}

internal fun validateFindElementResult(result: JsonObject, ios: Boolean) {
    result.validateFields("found", FieldType.BOOLEAN, required = true)
    validateElement(result, ios, interactive = false)
    result.validateFields("error", FieldType.STRING)
}

internal fun validateElement(element: JsonObject, ios: Boolean, interactive: Boolean) {
    element.validateFields("text resourceId className contentDescription bounds", FieldType.STRING)
    element.validateFields("isEnabled", FieldType.BOOLEAN)
    if (!ios) element.validateFields("isClickable", FieldType.BOOLEAN)
    else element.validateFields("value", FieldType.STRING)
    if (interactive) {
        element.validateFields("centerX centerY", FieldType.INTEGER)
        if (!ios) {
            element.validateFields("isCheckable isScrollable isLongClickable", FieldType.BOOLEAN)
        }
    }
}

internal enum class FieldType {
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

internal fun JsonObject.validateFields(fields: String, type: FieldType, required: Boolean = false) {
    for (field in fields.split(" ")) {
        if ((required || containsKey(field)) && !type.accepts(get(field))) {
            throw CommandExecutionException("Automation result.$field must be ${type.name.lowercase()}")
        }
    }
}
