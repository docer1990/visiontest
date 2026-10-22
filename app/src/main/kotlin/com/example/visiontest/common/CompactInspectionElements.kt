package com.example.visiontest.common

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val selectorFields = listOf("resourceId", "contentDescription", "text")
private val androidStates = mapOf(
    "isClickable" to "clickable", "isCheckable" to "checkable",
    "isScrollable" to "scrollable", "isLongClickable" to "longClickable",
)

internal fun selectorMatchCounts(elements: List<JsonObject>, ios: Boolean): Map<Pair<String, String>, Int> {
    val counts = mutableMapOf<Pair<String, String>, Int>()
    for (element in elements) {
        val keys = matchingKeys(element, ios)
        keys.forEach { counts[it] = counts.getOrDefault(it, 0) + 1 }
    }
    return counts
}

private fun matchingKeys(element: JsonObject, ios: Boolean): Set<Pair<String, String>> {
    val keys = selectorFields.mapNotNull { field ->
        element.presentation(field)?.let { field to it }
    }.toMutableSet()
    if (ios) keys += listOf("contentDescription", "value").mapNotNull { field ->
        element.presentation(field)?.let { "text" to it }
    }
    if (ios) keys += listOf("text", "contentDescription", "value").mapNotNull { field ->
        element.presentation(field)?.let { "resourceId" to it }
    }
    return keys
}

internal fun compactElement(
    element: JsonObject,
    ios: Boolean,
    includeBounds: Boolean,
    matches: Map<Pair<String, String>, Int>,
): JsonObject = buildJsonObject {
    val selector = selectorFields.firstNotNullOfOrNull { field ->
        element.presentation(field)?.takeIf { it.isNotBlank() }?.let { field to it }
    }
    put("selector", selector?.let { (field, value) -> buildJsonObject { put(field, value) } } ?: JsonNull)
    selector?.let { matches[it] }?.takeIf { it > 1 }?.let { put("selectorMatches", it) }
    addPresentation(element, ios)
    element["isEnabled"]?.let { put("enabled", it) }
    if (!ios) androidStates.forEach { (native, compact) -> element[native]?.let { put(compact, it) } }
    if (includeBounds) addBounds(element)
}

private fun JsonObjectBuilder.addPresentation(element: JsonObject, ios: Boolean) {
    val label = element.presentation("text") ?: element.presentation("contentDescription")
    label?.let { put("label", it) }
    element.presentation("className")?.let { put("type", it) }
    val description = element.presentation("contentDescription")
    description?.takeIf { it != label }?.let { put("contentDescription", it) }
    if (ios) element.presentation("value")?.takeIf { it != label && it != description }?.let { put("value", it) }
}

private fun JsonObjectBuilder.addBounds(element: JsonObject) {
    listOf("bounds", "centerX", "centerY").forEach { field ->
        element[field]?.takeUnless { field == "bounds" && it.jsonPrimitive.content.isEmpty() }?.let { put(field, it) }
    }
}

private fun JsonObject.presentation(field: String): String? =
    get(field)?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
