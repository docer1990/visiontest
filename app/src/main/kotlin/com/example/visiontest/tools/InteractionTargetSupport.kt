package com.example.visiontest.tools

import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.ios.IOSElementSelectors

internal const val INTERACTION_TOOL_TIMEOUT_MS = 45_000L
internal const val INTERACTION_DEFAULT_TIMEOUT_MS = 10_000
internal const val INTERACTION_MAX_TIMEOUT_MS = 30_000

private val KEY_ACTIONS = setOf("enter", "tab", "backspace", "delete", "escape")

internal sealed interface KeyInput {
    data class KeyCode(val value: Int) : KeyInput
    data class Action(val value: String) : KeyInput
}

internal sealed interface InteractionTarget {
    data class Coordinates(val x: Int, val y: Int) : InteractionTarget
    data class Element(val timeoutMs: Int) : InteractionTarget
}

internal data class InteractionGesture<S>(
    val coordinates: suspend (Int, Int) -> String,
    val element: suspend (S, Int) -> String,
)

internal fun validateKeyInput(keyCode: Int?, action: String?): KeyInput {
    require((keyCode == null) != (action == null)) { "Provide exactly one of keyCode or action" }
    return if (keyCode != null) {
        require(keyCode >= 0) { "keyCode must be nonnegative" }
        KeyInput.KeyCode(keyCode)
    } else {
        val normalized = requireNotNull(action).lowercase()
        require(normalized in KEY_ACTIONS) {
            "action must be one of: ${KEY_ACTIONS.joinToString()}"
        }
        KeyInput.Action(normalized)
    }
}

internal fun validateInteractionTarget(
    x: Int?,
    y: Int?,
    selectorValues: List<Pair<String, String?>>,
    selectorCount: Int,
    timeoutMs: Int?,
): InteractionTarget {
    val hasCoordinates = x != null || y != null
    val hasSelector = selectorCount > 0
    require(hasCoordinates != hasSelector) { "Provide exactly one target: coordinates or selectors" }
    return if (hasCoordinates) {
        require(x != null && y != null) { "Both x and y are required" }
        require(x >= 0 && y >= 0) { "Coordinates must be nonnegative" }
        require(timeoutMs == null) { "timeoutMs is only valid with selectors" }
        InteractionTarget.Coordinates(x, y)
    } else {
        InteractionTarget.Element(validateSelectorTimeout(selectorValues, timeoutMs))
    }
}

internal fun validateTargetedInput(
    selectorValues: List<Pair<String, String?>>,
    selectorCount: Int,
    timeoutMs: Int?,
): Int? {
    if (selectorCount == 0) {
        require(timeoutMs == null) { "timeoutMs is only valid with selectors" }
        return null
    }
    return validateSelectorTimeout(selectorValues, timeoutMs)
}

private fun validateSelectorTimeout(
    selectorValues: List<Pair<String, String?>>,
    timeoutMs: Int?,
): Int {
    selectorValues.forEach { (name, value) ->
        require(value == null || value.isNotBlank()) { "$name must not be blank" }
    }
    val resolved = timeoutMs ?: INTERACTION_DEFAULT_TIMEOUT_MS
    require(resolved in 1..INTERACTION_MAX_TIMEOUT_MS) {
        "timeoutMs must be between 1 and $INTERACTION_MAX_TIMEOUT_MS, got $resolved"
    }
    return resolved
}

internal fun AndroidElementSelectors.values() = listOf(
    "text" to text,
    "textContains" to textContains,
    "resourceId" to resourceId,
    "className" to className,
    "contentDescription" to contentDescription,
)

internal fun AndroidElementSelectors.count() = values().count { it.second != null }

internal fun IOSElementSelectors.values() = listOf(
    "text" to text,
    "textContains" to textContains,
    "resourceId" to identifier,
    "className" to elementType,
    "contentDescription" to label,
)

internal fun IOSElementSelectors.count() = values().count { it.second != null }

internal fun validateBundleId(bundleId: String?): String? {
    require(bundleId == null || bundleId.isNotBlank()) { "bundleId must not be blank" }
    return bundleId
}

internal fun validateAlertAction(action: String): String {
    val normalized = action.lowercase()
    require(normalized == "accept" || normalized == "dismiss") { "action must be accept or dismiss" }
    return normalized
}
