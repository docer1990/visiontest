package dev.visiontest.common

import dev.visiontest.CommandExecutionException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

internal data class AutomationResponse(val value: JsonObject, val isRpcError: Boolean)

internal fun automationResult(response: String): AutomationResponse {
    val body = parseResponseObject(response)
    val error = body["error"]?.takeUnless { it == JsonNull }
    return if (error != null) {
        AutomationResponse(formatRpcError(body, error), true)
    } else AutomationResponse(body["result"] as? JsonObject
        ?: throw CommandExecutionException("Automation response has no result object"), false)
}

private fun parseResponseObject(response: String): JsonObject {
    return try {
        strictJsonObject(response)
    } catch (e: IllegalArgumentException) {
        throw CommandExecutionException("Malformed automation response: ${e.message}").also {
            it.initCause(e)
        }
    }
}

private fun formatRpcError(body: JsonObject, error: JsonElement): JsonObject {
    val objectError = error as? JsonObject
    val code = objectError?.get("code") as? JsonPrimitive
    val message = objectError?.get("message") as? JsonPrimitive
    val valid = !body.containsKey("result") && code?.intOrNull != null && !code.isString && message?.isString == true
    if (!valid) throw CommandExecutionException("Invalid automation error response")
    return buildJsonObject { put("error", error) }
}
