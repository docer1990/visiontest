package com.example.automationserver.uiautomator

import android.view.KeyEvent
import com.google.gson.JsonElement
import com.google.gson.JsonObject

private const val DEFAULT_INTERACTION_TIMEOUT_MS = 10_000
private val INTEGER_JSON_NUMBER = Regex("-?(0|[1-9]\\d*)")

sealed interface NativeGestureTarget {
    data class Coordinates(val x: Int, val y: Int) : NativeGestureTarget
    data class Element(
        val selectors: TapOnElementSelectors,
        val timeoutMs: Int,
    ) : NativeGestureTarget
}

data class NativeGestureRequest(val target: NativeGestureTarget)

data class TargetedInputRequest(
    val text: String,
    val selectors: TapOnElementSelectors? = null,
    val timeoutMs: Int? = null,
)

fun parseNamedKeyAction(action: String): Int = when (action) {
    "enter" -> KeyEvent.KEYCODE_ENTER
    "tab" -> KeyEvent.KEYCODE_TAB
    "backspace" -> KeyEvent.KEYCODE_DEL
    "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
    "escape" -> KeyEvent.KEYCODE_ESCAPE
    else -> throw IllegalArgumentException(
        "'action' must be one of: enter, tab, backspace, delete, escape"
    )
}

fun parseKeyRequest(params: JsonObject?): Int {
    val keyCodeValue = params?.get("keyCode")
    val actionValue = params?.get("action")
    require((keyCodeValue == null) xor (actionValue == null)) {
        "Exactly one of 'keyCode' or 'action' is required"
    }
    return if (keyCodeValue != null) {
        parseStrictInteger(keyCodeValue, "keyCode").also {
            require(it >= 0) { "'keyCode' must be nonnegative" }
        }
    } else {
        parseString(actionValue, "action").let(::parseNamedKeyAction)
    }
}

fun parseGestureRequest(params: JsonObject?): NativeGestureRequest {
    val xValue = params?.get("x")
    val yValue = params?.get("y")
    val selectors = parseSelectors(params)
    val hasCoordinates = xValue != null || yValue != null
    val hasSelectors = selectors != null
    require(hasCoordinates xor hasSelectors) {
        "Provide either both coordinates or at least one selector"
    }
    return if (hasCoordinates) {
        require(xValue != null && yValue != null) { "Both 'x' and 'y' are required" }
        require(params?.get("timeoutMs") == null) { "'timeoutMs' is only valid with selectors" }
        val x = parseStrictInteger(xValue, "x")
        val y = parseStrictInteger(yValue, "y")
        require(x >= 0 && y >= 0) { "Coordinates must be nonnegative" }
        NativeGestureRequest(NativeGestureTarget.Coordinates(x, y))
    } else {
        val timeoutMs = parseTimeout(params?.get("timeoutMs"))
        NativeGestureRequest(NativeGestureTarget.Element(requireNotNull(selectors), timeoutMs))
    }
}

fun parseTargetedInputRequest(params: JsonObject?): TargetedInputRequest {
    val text = parseString(params?.get("text"), "text", allowBlank = true)
    val selectors = parseSelectors(params, prefix = "target")
    val timeoutValue = params?.get("timeoutMs")
    require(selectors != null || timeoutValue == null) {
        "'timeoutMs' is only valid with target selectors"
    }
    return TargetedInputRequest(
        text = text,
        selectors = selectors,
        timeoutMs = selectors?.let { parseTimeout(timeoutValue) },
    )
}

private fun parseSelectors(params: JsonObject?, prefix: String = ""): TapOnElementSelectors? {
    fun name(base: String) = if (prefix.isEmpty()) base else prefix + base.replaceFirstChar(Char::uppercase)
    val selectors = TapOnElementSelectors(
        text = optionalString(params?.get(name("text")), name("text")),
        textContains = optionalString(params?.get(name("textContains")), name("textContains")),
        resourceId = optionalString(params?.get(name("resourceId")), name("resourceId")),
        className = optionalString(params?.get(name("className")), name("className")),
        contentDescription = optionalString(
            params?.get(name("contentDescription")),
            name("contentDescription")
        ),
    )
    val values = listOf(
        selectors.text,
        selectors.textContains,
        selectors.resourceId,
        selectors.className,
        selectors.contentDescription,
    )
    return selectors.takeIf { values.any { value -> value != null } }
}

private fun parseTimeout(value: JsonElement?): Int {
    if (value == null) return DEFAULT_INTERACTION_TIMEOUT_MS
    return parseStrictInteger(value, "timeoutMs").also {
        require(it in 1..MAX_TAP_TIMEOUT_MS) {
            "'timeoutMs' must be an integer between 1 and $MAX_TAP_TIMEOUT_MS"
        }
    }
}

private fun parseStrictInteger(value: JsonElement, name: String): Int {
    val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
    require(text != null && INTEGER_JSON_NUMBER.matches(text)) { "'$name' must be an integer" }
    return requireNotNull(text.toIntOrNull()) { "'$name' is outside the supported integer range" }
}

private fun optionalString(value: JsonElement?, name: String): String? =
    value?.let { parseString(it, name) }

private fun parseString(value: JsonElement?, name: String, allowBlank: Boolean = false): String {
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "'$name' must be a string"
    }
    return value.asString.also {
        require(allowBlank || it.isNotBlank()) { "'$name' must be a nonblank string" }
    }
}
