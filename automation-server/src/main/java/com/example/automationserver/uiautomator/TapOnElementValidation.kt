package com.example.automationserver.uiautomator

import com.google.gson.JsonElement
import com.google.gson.JsonObject

private const val TIMEOUT_ERROR = "'timeoutMs' must be an integer between 1 and 30000"
const val MAX_TAP_TIMEOUT_MS = 30_000
private val INTEGER_JSON_NUMBER = Regex("0|[1-9]\\d*")

data class TapOnElementSelectors(
    val text: String? = null,
    val textContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val contentDescription: String? = null
)

data class TapOnElementRequest(
    val selectors: TapOnElementSelectors,
    val timeoutMs: Int
)

fun parseTapOnElementTimeoutMs(timeoutParameter: JsonElement?): Int {
    val timeoutText = timeoutParameter
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString
    require(timeoutText != null && INTEGER_JSON_NUMBER.matches(timeoutText)) { TIMEOUT_ERROR }

    val timeoutMs = timeoutText.toIntOrNull()
    require(timeoutMs != null && timeoutMs in 1..MAX_TAP_TIMEOUT_MS) { TIMEOUT_ERROR }
    return timeoutMs
}

fun validateTapOnElementSelectors(
    text: String? = null,
    textContains: String? = null,
    resourceId: String? = null,
    className: String? = null,
    contentDescription: String? = null
) {
    val selectors = listOf(text, textContains, resourceId, className, contentDescription)
    require(selectors.any { it != null }) {
        "At least one selector required: text, textContains, resourceId, className, or contentDescription"
    }
    require(selectors.none { it?.isBlank() == true }) { "Selector values must not be blank" }
}

fun parseTapOnElementSelectors(params: JsonObject?): TapOnElementSelectors {
    fun selector(name: String): String? {
        val value = params?.get(name) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "'$name' must be a string" }
        return value.asString
    }

    return TapOnElementSelectors(
        text = selector("text"),
        textContains = selector("textContains"),
        resourceId = selector("resourceId"),
        className = selector("className"),
        contentDescription = selector("contentDescription")
    ).also {
        validateTapOnElementSelectors(
            it.text,
            it.textContains,
            it.resourceId,
            it.className,
            it.contentDescription
        )
    }
}
