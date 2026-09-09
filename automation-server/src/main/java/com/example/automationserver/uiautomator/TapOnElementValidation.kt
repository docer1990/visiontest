package com.example.automationserver.uiautomator

import com.google.gson.JsonElement
import com.google.gson.JsonObject

private const val TIMEOUT_ERROR = "'timeoutMs' must be an integer between 1 and 30000"
private val INTEGER_JSON_NUMBER = Regex("0|[1-9]\\d*")

data class TapOnElementSelectors(
    val text: String? = null,
    val textContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val contentDescription: String? = null
)

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

fun parseTapOnElementSelectors(params: JsonObject?): TapOnElementSelectors {
    fun selector(name: String): String? {
        val value = params?.get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw IllegalArgumentException("'$name' must be a string")
        }
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
