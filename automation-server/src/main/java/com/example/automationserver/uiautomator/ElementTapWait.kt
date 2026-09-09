package com.example.automationserver.uiautomator

data class ElementTapCandidate<T>(
    val element: T,
    val enabled: Boolean,
    val hasVisibleBounds: Boolean
)

data class ElementTapWaitResult(
    val success: Boolean,
    val error: String? = null
)

data class ElementTapClock(
    val nowMs: () -> Long,
    val sleepMs: (Long) -> Unit
)

data class ElementTapOperation<T>(
    val lookup: () -> ElementTapCandidate<T>?,
    val tap: (T) -> Unit
)

private const val ELEMENT_TAP_POLL_INTERVAL_MS = 500L

@Suppress("TooGenericExceptionCaught")
fun <T> waitAndTapElement(
    timeoutMs: Long,
    elementDescription: String = "element",
    clock: ElementTapClock,
    operation: ElementTapOperation<T>
): ElementTapWaitResult {
    try {
        return runElementTapLoop(timeoutMs, elementDescription, clock, operation)
    } catch (error: Exception) {
        return ElementTapWaitResult(success = false, error = error.message ?: "Unexpected element tap error")
    }
}

private fun <T> runElementTapLoop(
    timeoutMs: Long,
    elementDescription: String,
    clock: ElementTapClock,
    operation: ElementTapOperation<T>
): ElementTapWaitResult {
    val startMs = clock.nowMs()
    var foundElement = false
    var result: ElementTapWaitResult? = null
    while (result == null) {
        val candidate = operation.lookup()
        val elapsedMs = clock.nowMs() - startMs
        if (elapsedMs > timeoutMs) {
            result = timeoutResult(foundElement, timeoutMs, elementDescription)
        } else if (candidate?.let { it.enabled && it.hasVisibleBounds } == true) {
            operation.tap(candidate.element)
            result = ElementTapWaitResult(success = true)
        } else {
            foundElement = foundElement || candidate != null
            result = if (elapsedMs >= timeoutMs) {
                timeoutResult(foundElement, timeoutMs, elementDescription)
            } else {
                clock.sleepMs(minOf(ELEMENT_TAP_POLL_INTERVAL_MS, timeoutMs - elapsedMs))
                null
            }
        }
    }
    return result
}

private fun timeoutResult(
    foundElement: Boolean,
    timeoutMs: Long,
    elementDescription: String
) = ElementTapWaitResult(
    success = false,
    error = if (foundElement) {
        "Element found but not tappable within ${timeoutMs}ms: $elementDescription"
    } else {
        "Element not found within ${timeoutMs}ms: $elementDescription"
    }
)
