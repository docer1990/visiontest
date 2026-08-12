package com.example.visiontest.ios

import com.example.visiontest.common.describeSelectors

/**
 * Selector set for locating an iOS element, mirroring [IOSAutomationClient.findElement]'s
 * parameters. Grouped into a value type so wait tool call sites stay compact.
 */
data class IOSElementSelectors(
    val text: String? = null,
    val textContains: String? = null,
    val identifier: String? = null,
    val elementType: String? = null,
    val label: String? = null,
    val bundleId: String? = null,
) {
    /** True when at least one element selector is set (bundleId alone does not select). */
    fun hasAnySelector(): Boolean =
        text != null || textContains != null || identifier != null ||
            elementType != null || label != null

    /** Human-readable summary used in wait/timeout messages. */
    fun describe(): String = describeSelectors(
        "text" to text,
        "textContains" to textContains,
        "identifier" to identifier,
        "elementType" to elementType,
        "label" to label,
    )
}
