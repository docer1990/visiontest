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

fun <T> waitAndTapElement(
    timeoutMs: Long,
    elementDescription: String = "element",
    nowMs: () -> Long,
    sleepMs: (Long) -> Unit,
    lookup: () -> ElementTapCandidate<T>?,
    tap: (T) -> Unit
): ElementTapWaitResult {
    val startMs = nowMs()
    var foundElement = false
    fun timeoutResult() = ElementTapWaitResult(
        success = false,
        error = if (foundElement) {
            "Element found but not tappable within ${timeoutMs}ms: $elementDescription"
        } else {
            "Element not found within ${timeoutMs}ms: $elementDescription"
        }
    )

    try {
        while (true) {
            val candidate = lookup()
            if (nowMs() - startMs > timeoutMs) {
                return timeoutResult()
            }
            if (candidate != null) {
                foundElement = true
                if (candidate.enabled && candidate.hasVisibleBounds) {
                    tap(candidate.element)
                    return ElementTapWaitResult(success = true)
                }
            }

            val elapsedMs = nowMs() - startMs
            if (elapsedMs >= timeoutMs) {
                return timeoutResult()
            }
            sleepMs(minOf(500L, timeoutMs - elapsedMs))
        }
    } catch (e: Exception) {
        return ElementTapWaitResult(success = false, error = e.message ?: "Unexpected element tap error")
    }
}
