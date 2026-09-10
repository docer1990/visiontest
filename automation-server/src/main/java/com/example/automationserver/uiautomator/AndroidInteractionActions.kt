package com.example.automationserver.uiautomator

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

private const val LONG_PRESS_DURATION_MS = 800L
private const val LONG_PRESS_SWIPE_STEPS = 160
private const val DOUBLE_TAP_INTERVAL_MS = 100L

internal class AndroidInteractionActions(
    private val device: UiDevice,
    private val displayRect: Rect,
    private val selectorBuilder: (TapOnElementSelectors) -> BySelector?,
    private val selectorDescription: (TapOnElementSelectors) -> String,
) {
    fun longPress(request: NativeGestureRequest): OperationResult = when (val target = request.target) {
        is NativeGestureTarget.Coordinates -> coordinateGesture(target.x, target.y) { x, y ->
            device.swipe(x, y, x, y, LONG_PRESS_SWIPE_STEPS)
        }
        is NativeGestureTarget.Element -> elementGesture(target) { it.click(LONG_PRESS_DURATION_MS) }
    }

    fun doubleTap(request: NativeGestureRequest): OperationResult = when (val target = request.target) {
        is NativeGestureTarget.Coordinates -> coordinateGesture(target.x, target.y) { x, y ->
            val first = device.click(x, y)
            SystemClock.sleep(DOUBLE_TAP_INTERVAL_MS)
            device.click(x, y) && first
        }
        is NativeGestureTarget.Element -> elementGesture(target) {
            it.click()
            SystemClock.sleep(DOUBLE_TAP_INTERVAL_MS)
            it.click()
        }
    }

    fun targetedInput(request: TargetedInputRequest): OperationResult {
        val selectors = requireNotNull(request.selectors)
        val timeoutMs = requireNotNull(request.timeoutMs)
        val selector = selectorBuilder(selectors)
            ?: return OperationResult(success = false, error = "No selector provided")
        val result = waitForTargetFocusAndInput(
            timeoutMs = timeoutMs.toLong(),
            description = selectorDescription(selectors),
            clock = ElementInteractionClock(SystemClock::elapsedRealtime, SystemClock::sleep),
            operation = TargetedInputOperation(
                lookup = { findCandidate(selector, requireFocusable = true) },
                tap = UiObject2::click,
                hasEditableFocus = UiObject2::isFocused,
                input = { it.text = request.text },
            ),
        )
        return OperationResult(success = result.success, error = result.error)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun coordinateGesture(x: Int, y: Int, gesture: (Int, Int) -> Boolean): OperationResult {
        if (!displayRect.contains(x, y)) {
            return OperationResult(success = false, error = "Coordinates are outside the display")
        }
        return try {
            val success = gesture(x, y)
            OperationResult(
                success = success,
                error = if (success) null else "Native coordinate gesture failed",
            )
        } catch (error: RuntimeException) {
            OperationResult(success = false, error = error.message)
        }
    }

    private fun elementGesture(
        target: NativeGestureTarget.Element,
        gesture: (UiObject2) -> Unit,
    ): OperationResult {
        val selector = selectorBuilder(target.selectors)
            ?: return OperationResult(success = false, error = "No selector provided")
        val result = waitForTargetAndInteract(
            timeoutMs = target.timeoutMs.toLong(),
            description = selectorDescription(target.selectors),
            clock = ElementInteractionClock(SystemClock::elapsedRealtime, SystemClock::sleep),
            lookup = { findCandidate(selector) },
            interact = gesture,
        )
        return OperationResult(success = result.success, error = result.error)
    }

    private fun findCandidate(
        selector: BySelector,
        requireFocusable: Boolean = false,
    ): ElementInteractionCandidate<UiObject2>? = device.findObject(selector)?.let { element ->
        val bounds = element.visibleBounds
        val visible = bounds.width() > 0 && bounds.height() > 0 && Rect.intersects(bounds, displayRect)
        val ready = visible && element.isEnabled && (!requireFocusable || element.isFocusable)
        ElementInteractionCandidate(
            element,
            if (ready) ElementInteractionReadiness.READY else ElementInteractionReadiness.BLOCKED,
        )
    }
}
