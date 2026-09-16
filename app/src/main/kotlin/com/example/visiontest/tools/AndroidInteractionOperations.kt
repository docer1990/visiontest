package com.example.visiontest.tools

import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.android.AutomationClient

internal class AndroidInteractionOperations(
    private val client: AutomationClient,
    private val requireServer: suspend () -> Unit,
) {
    suspend fun pressKey(keyCode: Int?, action: String?): String {
        val input = validateKeyInput(keyCode, action)
        requireServer()
        return when (input) {
            is KeyInput.KeyCode -> client.pressKey(input.value)
            is KeyInput.Action -> client.pressKey(input.value)
        }
    }

    suspend fun clearText(): String {
        requireServer()
        return client.clearText()
    }

    suspend fun longPress(
        x: Int?,
        y: Int?,
        selectors: AndroidElementSelectors,
        timeoutMs: Int?,
    ): String = performGesture(
        x, y, selectors, timeoutMs, InteractionGesture(client::longPress, client::longPress)
    )

    suspend fun doubleTap(
        x: Int?,
        y: Int?,
        selectors: AndroidElementSelectors,
        timeoutMs: Int?,
    ): String = performGesture(
        x, y, selectors, timeoutMs, InteractionGesture(client::doubleTap, client::doubleTap)
    )

    suspend fun inputText(
        text: String,
        selectors: AndroidElementSelectors?,
        timeoutMs: Int?,
    ): String {
        val selectorValues = selectors?.values().orEmpty()
        val selectorCount = selectors?.count() ?: 0
        val resolvedTimeout = validateTargetedInput(selectorValues, selectorCount, timeoutMs)
        requireServer()
        return client.inputText(text, selectors?.takeIf { selectorCount > 0 }, resolvedTimeout)
    }

    private suspend fun performGesture(
        x: Int?,
        y: Int?,
        selectors: AndroidElementSelectors,
        timeoutMs: Int?,
        gesture: InteractionGesture<AndroidElementSelectors>,
    ): String {
        val target = validateInteractionTarget(x, y, selectors.values(), selectors.count(), timeoutMs)
        requireServer()
        return when (target) {
            is InteractionTarget.Coordinates -> gesture.coordinates(target.x, target.y)
            is InteractionTarget.Element -> gesture.element(selectors, target.timeoutMs)
        }
    }
}
