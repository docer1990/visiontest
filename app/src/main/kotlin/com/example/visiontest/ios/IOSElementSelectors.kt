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

    /**
     * Human-readable summary used in wait/timeout messages.
     *
     * Uses the tool-facing parameter names (`resourceId`, `className`,
     * `contentDescription`), not this class's internal XCUITest-flavored ones, so a
     * message names the parameters the caller actually passed. `bundleId` is included
     * because waiting against Springboard instead of the app is a common iOS mistake.
     */
    fun describe(): String = describeSelectors(
        "text" to text,
        "textContains" to textContains,
        "resourceId" to identifier,
        "className" to elementType,
        "contentDescription" to label,
        "bundleId" to bundleId,
    )
}
