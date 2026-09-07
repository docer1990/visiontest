package com.example.visiontest.cli

import com.example.visiontest.CommandExecutionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

internal fun inspectionOutput(response: String, json: Boolean): String {
    return if (json) unwrapInspectionResult(response) else response
}

private fun unwrapInspectionResult(response: String): String {
    val body = parseResponseObject(response)
    val error = body["error"]?.takeUnless { it == JsonNull }
    return if (error != null) formatRpcError(body, error) else {
        (body["result"] as? JsonObject)?.toString()
            ?: throw CommandExecutionException("Automation response has no result object")
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

private fun formatRpcError(body: JsonObject, error: kotlinx.serialization.json.JsonElement): String {
    val objectError = error as? JsonObject
    val code = objectError?.get("code") as? JsonPrimitive
    val message = objectError?.get("message") as? JsonPrimitive
    val valid = !body.containsKey("result") && code?.intOrNull != null && !code.isString && message?.isString == true
    if (!valid) throw CommandExecutionException("Invalid automation error response")
    return buildJsonObject { put("error", error) }.toString()
}
