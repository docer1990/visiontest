package com.example.visiontest.tools

import com.example.visiontest.CommandExecutionException
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val ELEMENT_TAP_TOOL_TIMEOUT_MS = 45_000L
private const val ELEMENT_TAP_MIN_TIMEOUT_MS = 1
private const val ELEMENT_TAP_MAX_TIMEOUT_MS = 30_000
private const val ELEMENT_TAP_DEFAULT_TIMEOUT_MS = 10_000

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
    val body = parseElementTapResponse(response)
    if (body.has("error")) {
        elementTapFailure("Element tap failed: ${errorMessage(body["error"])}")
    }
    val result = body["result"]?.takeIf { it.isJsonObject }?.asJsonObject
        ?: elementTapFailure("Element tap response has no result object")
    val success = result["success"]
    if (success == null || !success.isJsonPrimitive || !success.asJsonPrimitive.isBoolean) {
        elementTapFailure("Element tap response has no boolean success field")
    }
    if (!success.asBoolean) {
        elementTapFailure("Element tap failed: ${errorMessage(result["error"])}")
    }
    return response
}

private fun parseElementTapResponse(response: String): JsonObject {
    val body = try {
        JsonParser.parseString(response).asJsonObject
    } catch (error: JsonParseException) {
        malformedElementTapResponse(error)
    } catch (error: IllegalStateException) {
        malformedElementTapResponse(error)
    }
    return body
}

private fun malformedElementTapResponse(error: Exception): Nothing =
    elementTapFailure("Malformed element tap response: ${error.message}", error)

private fun elementTapFailure(message: String, cause: Exception? = null): Nothing {
    val exception = CommandExecutionException(message)
    cause?.let(exception::initCause)
    throw exception
}

private fun errorMessage(error: JsonElement?): String =
    (error as? JsonObject)?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        ?: error?.takeIf { it.isJsonPrimitive }?.asString
        ?: error?.toString()
        ?: "automation server reported an unsuccessful operation"

internal fun registerElementTapTool(
    scope: ToolScope,
    name: String,
    description: String,
    selectorNames: List<String>,
    tap: suspend (CallToolRequest) -> String,
) {
    scope.tool(
        name = name,
        description = description,
        inputSchema = elementTapInputSchema(selectorNames),
        timeoutMs = ELEMENT_TAP_TOOL_TIMEOUT_MS,
    ) { request -> tap(request) }
}

private fun elementTapInputSchema(selectorNames: List<String>) = Tool.Input(properties = buildJsonObject {
    selectorNames.forEach { name -> putJsonObject(name) { put("type", "string") } }
    putJsonObject("timeoutMs") {
        put("type", "integer")
        put("minimum", ELEMENT_TAP_MIN_TIMEOUT_MS)
        put("maximum", ELEMENT_TAP_MAX_TIMEOUT_MS)
        put("default", ELEMENT_TAP_DEFAULT_TIMEOUT_MS)
    }
})
