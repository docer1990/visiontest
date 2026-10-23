package dev.visiontest.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun strictJsonObject(response: String): JsonObject {
    require(!containsInvalidJsonControls(response)) { "JSON contains an unescaped control character" }
    val result = Json.parseToJsonElement(response)
    require(hasValidJsonPrimitives(result)) { "JSON contains an invalid literal" }
    return result as? JsonObject ?: throw IllegalArgumentException("JSON response must be an object")
}

private val jsonBooleanLiterals = setOf("true", "false")
private val jsonNumberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/** The tree parser retains arbitrary bare tokens; require RFC JSON literals throughout the body. */
internal fun hasValidJsonPrimitives(root: JsonElement): Boolean {
    val pending = ArrayDeque<JsonElement>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        when (val element = pending.removeLast()) {
            is JsonObject -> pending.addAll(element.values)
            is JsonArray -> pending.addAll(element)
            JsonNull -> Unit
            is JsonPrimitive -> if (!element.isString && element.content !in jsonBooleanLiterals &&
                !jsonNumberPattern.matches(element.content)) return false
        }
    }
    return true
}

/** Raw string controls are invalid; escaped controls and formatting whitespace remain valid. */
internal fun containsInvalidJsonControls(response: String): Boolean {
    var quoted = false
    var escaped = false
    for (character in response) {
        if (character < ' ' && (quoted || character !in "\t\r\n")) return true
        if (escaped) {
            escaped = false
            continue
        }
        when (character) {
            '\\' -> escaped = quoted
            '"' -> quoted = !quoted
        }
    }
    return false
}
