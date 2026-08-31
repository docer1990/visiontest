package com.example.visiontest.android

import com.example.visiontest.common.describeSelectors

/**
 * Selector set for locating an Android element, mirroring [AutomationClient.findElement]'s
 * parameters. Grouped into a value type so wait tool call sites stay compact.
 */
data class AndroidElementSelectors(
    val text: String? = null,
    val textContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val contentDescription: String? = null,
) {
    fun hasAnySelector(): Boolean =
        text != null || textContains != null || resourceId != null ||
            className != null || contentDescription != null

    /** Human-readable summary used in wait/timeout messages. */
    fun describe(): String = describeSelectors(
        "text" to text,
        "textContains" to textContains,
        "resourceId" to resourceId,
        "className" to className,
        "contentDescription" to contentDescription,
    )
}
