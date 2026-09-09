package com.example.automationserver.uiautomator

import com.google.gson.JsonElement

private const val TIMEOUT_ERROR = "'timeoutMs' must be an integer between 1 and 30000"
private val INTEGER_JSON_NUMBER = Regex("0|[1-9]\\d*")

fun parseTapOnElementTimeoutMs(timeoutParameter: JsonElement?): Int {
    if (timeoutParameter == null || !timeoutParameter.isJsonPrimitive ||
        !timeoutParameter.asJsonPrimitive.isNumber) {
        throw IllegalArgumentException(TIMEOUT_ERROR)
    }
    val timeoutText = timeoutParameter.asString
    if (!INTEGER_JSON_NUMBER.matches(timeoutText)) {
        throw IllegalArgumentException(TIMEOUT_ERROR)
    }
    val timeoutMs = timeoutText.toIntOrNull() ?: throw IllegalArgumentException(TIMEOUT_ERROR)
    if (timeoutMs !in 1..30_000) {
        throw IllegalArgumentException(TIMEOUT_ERROR)
    }
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
    if (selectors.all { it == null }) {
        throw IllegalArgumentException(
            "At least one selector required: text, textContains, resourceId, className, or contentDescription"
        )
    }
    if (selectors.any { it?.isBlank() == true }) {
        throw IllegalArgumentException("Selector values must not be blank")
    }
}
