package com.example.visiontest.tools

import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSElementSelectors

internal class IOSInteractionOperations(
    private val client: IOSAutomationClient,
    private val requireServer: suspend () -> Unit,
) {
    suspend fun dismissKeyboard(bundleId: String?): String {
        val scope = validateBundleId(bundleId)
        requireServer()
        return client.dismissKeyboard(scope)
    }

    suspend fun handleAlert(action: String, buttonLabel: String?, bundleId: String?): String {
        val normalizedAction = validateAlertAction(action)
        require(buttonLabel == null || buttonLabel.isNotBlank()) { "buttonLabel must not be blank" }
        val scope = validateBundleId(bundleId)
        requireServer()
        return client.handleAlert(normalizedAction, buttonLabel, scope)
    }

    suspend fun longPress(
        x: Int?, y: Int?, selectors: IOSElementSelectors, timeoutMs: Int?
    ): String = performGesture(
        x, y, selectors, timeoutMs, InteractionGesture(client::longPress, client::longPress)
    )

    suspend fun doubleTap(
        x: Int?, y: Int?, selectors: IOSElementSelectors, timeoutMs: Int?
    ): String = performGesture(
        x, y, selectors, timeoutMs, InteractionGesture(client::doubleTap, client::doubleTap)
    )

    suspend fun inputText(
        text: String,
        bundleId: String?,
        selectors: IOSElementSelectors?,
        timeoutMs: Int?,
    ): String {
        val selectorCount = selectors?.count() ?: 0
        val timeout = validateTargetedInput(selectors?.values().orEmpty(), selectorCount, timeoutMs)
        val selectorScope = validateBundleId(selectors?.bundleId)
        val explicitScope = validateBundleId(bundleId)
        require(explicitScope == null || selectorScope == null || explicitScope == selectorScope) {
            "bundleId must identify one app scope"
        }
        requireServer()
        return client.inputText(text, explicitScope ?: selectorScope, selectors, timeout)
    }

    private suspend fun performGesture(
        x: Int?,
        y: Int?,
        selectors: IOSElementSelectors,
        timeoutMs: Int?,
        gesture: InteractionGesture<IOSElementSelectors>,
    ): String {
        validateBundleId(selectors.bundleId)
        val target = validateInteractionTarget(x, y, selectors.values(), selectors.count(), timeoutMs)
        if (target is InteractionTarget.Coordinates) {
            require(selectors.bundleId == null) { "bundleId is only valid with selectors" }
        }
        requireServer()
        return when (target) {
            is InteractionTarget.Coordinates -> gesture.coordinates(target.x, target.y)
            is InteractionTarget.Element -> gesture.element(selectors, target.timeoutMs)
        }
    }
}
