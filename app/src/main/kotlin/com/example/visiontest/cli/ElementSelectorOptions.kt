package com.example.visiontest.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option

internal class ElementSelectorOptions : OptionGroup("Element selectors") {
    val text by option("--text", help = "Exact text match")
    val textContains by option("--text-contains", help = "Partial text match")
    val resourceId by option("--resource-id", help = "Resource ID (Android) / accessibility identifier (iOS)")
    val className by option("--class-name", help = "Class name (Android) / element type (iOS)")
    val contentDescription by option("--content-description", help = "Content description (Android) / label (iOS)")
    fun validate() {
        val values = listOf(text, textContains, resourceId, className, contentDescription)
        require(values.any { !it.isNullOrBlank() }) {
            "At least one element selector is required"
        }
        require(values.filterNotNull().all { it.isNotBlank() }) { "Element selectors must not be blank" }
    }
}
