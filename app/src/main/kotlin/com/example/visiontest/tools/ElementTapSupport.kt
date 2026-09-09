package com.example.visiontest.tools

import com.example.visiontest.CommandExecutionException
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal const val ELEMENT_TAP_TOOL_TIMEOUT_MS = 45_000L

internal fun validateElementTap(
    selectorValues: List<Pair<String, String?>>,
    hasSelector: Boolean,
    timeoutMs: Int?,
    defaultTimeoutMs: Long,
    maxTimeoutMs: Long,
) : Int {
    require(hasSelector) {
        "At least one selector required (text, textContains, resourceId, className, or contentDescription)"
    }
    selectorValues.forEach { (name, value) ->
        require(value == null || value.isNotBlank()) { "$name must not be blank" }
    }
    val resolvedTimeout = timeoutMs ?: defaultTimeoutMs.toInt()
    require(resolvedTimeout.toLong() in 1..maxTimeoutMs) {
        "timeoutMs must be between 1 and $maxTimeoutMs, got $resolvedTimeout"
    }
    return resolvedTimeout
}

internal fun successfulElementTapResponse(response: String): String {
    val body = try {
        JsonParser.parseString(response).asJsonObject
    } catch (error: Exception) {
        throw CommandExecutionException("Malformed element tap response: ${error.message}")
    }
    if (body.has("error")) {
        throw CommandExecutionException("Element tap failed: ${errorMessage(body["error"])}")
    }
    val result = body["result"]?.takeIf { it.isJsonObject }?.asJsonObject
        ?: throw CommandExecutionException("Element tap response has no result object")
    val success = result["success"]
    if (success == null || !success.isJsonPrimitive || !success.asJsonPrimitive.isBoolean) {
        throw CommandExecutionException("Element tap response has no boolean success field")
    }
    if (!success.asBoolean) {
        throw CommandExecutionException("Element tap failed: ${errorMessage(result["error"])}")
    }
    return response
}

private fun errorMessage(error: JsonElement?): String =
    (error as? JsonObject)?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        ?: error?.takeIf { it.isJsonPrimitive }?.asString
        ?: error?.toString()
        ?: "automation server reported an unsuccessful operation"
