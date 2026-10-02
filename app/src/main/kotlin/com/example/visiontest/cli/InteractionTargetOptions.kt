package com.example.visiontest.cli

import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.ios.IOSElementSelectors
import com.example.visiontest.tools.InteractionTarget
import com.example.visiontest.tools.count
import com.example.visiontest.tools.validateBundleId
import com.example.visiontest.tools.validateInteractionTarget
import com.example.visiontest.tools.validateTargetedInput
import com.example.visiontest.tools.values
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

internal open class InteractionSelectorOptions(prefix: String, name: String) : OptionGroup(name) {
    private val text by option("${prefix}text", help = "Exact target text match")
    private val textContains by option("${prefix}text-contains", help = "Partial target text match")
    private val resourceId by option("${prefix}resource-id", help = "Resource ID / accessibility identifier")
    private val className by option("${prefix}class-name", help = "Class name / element type")
    private val contentDescription by option("${prefix}content-description", help = "Content description / label")
    val timeout by option("--timeout", help = "Selector wait in milliseconds (default 10000, range 1..30000)").int()

    fun androidSelectors() = AndroidElementSelectors(text, textContains, resourceId, className, contentDescription)

    fun iosSelectors(bundleId: String?) =
        IOSElementSelectors(text, textContains, resourceId, className, contentDescription, bundleId)
}

internal class InteractionTargetOptions : InteractionSelectorOptions("--", "Interaction target") {
    val x by option("--x", help = "Screen X coordinate").int()
    val y by option("--y", help = "Screen Y coordinate").int()
    val bundleId by option("--bundle-id", help = "Target app bundle ID on iOS (selector targets only)")

    fun validate(platform: Platform) {
        require(platform == Platform.Ios || bundleId == null) { "--bundle-id is only supported on iOS" }
        validateBundleId(bundleId)
        val selectors = androidSelectors()
        val target = validateInteractionTarget(x, y, selectors.values(), selectors.count(), timeout)
        if (target is InteractionTarget.Coordinates) {
            require(bundleId == null) { "--bundle-id is only valid with selectors" }
        }
    }
}

internal class InputTextTargetOptions : InteractionSelectorOptions("--target-", "Input target selectors") {
    fun validate() {
        val selectors = androidSelectors()
        validateTargetedInput(selectors.values(), selectors.count(), timeout)
    }
}
