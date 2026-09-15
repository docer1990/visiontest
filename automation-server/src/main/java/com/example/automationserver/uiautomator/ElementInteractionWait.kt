package com.example.automationserver.uiautomator

enum class ElementInteractionReadiness {
    BLOCKED,
    READY,
}

data class ElementInteractionCandidate<T>(
    val element: T,
    val readiness: ElementInteractionReadiness,
)

data class ElementInteractionClock(
    val nowMs: () -> Long,
    val sleepMs: (Long) -> Unit,
)

data class TargetedInputOperation<T>(
    val lookup: () -> ElementInteractionCandidate<T>?,
    val tap: (T) -> Unit,
    val hasEditableFocus: (T) -> Boolean,
    val input: (T) -> ElementTapWaitResult,
)

private const val INTERACTION_POLL_INTERVAL_MS = 500L

fun <T> waitForTargetAndInteract(
    timeoutMs: Long,
    description: String,
    clock: ElementInteractionClock,
    lookup: () -> ElementInteractionCandidate<T>?,
    interact: (T) -> Unit,
): ElementTapWaitResult = runInteractionSafely {
    val deadline = clock.nowMs() + timeoutMs
    var observedBlocked = false
    var result: ElementTapWaitResult? = null
    while (result == null) {
        val candidate = lookup()
        val now = clock.nowMs()
        if (candidate?.readiness == ElementInteractionReadiness.READY && now <= deadline) {
            interact(candidate.element)
            result = ElementTapWaitResult(success = true)
        } else {
            observedBlocked = observedBlocked || candidate != null
            if (now >= deadline) {
                result = targetTimeout(observedBlocked, timeoutMs, description)
            } else {
                clock.sleepMs(minOf(INTERACTION_POLL_INTERVAL_MS, deadline - now))
            }
        }
    }
    result
}

fun <T> waitForTargetFocusAndInput(
    timeoutMs: Long,
    description: String,
    clock: ElementInteractionClock,
    operation: TargetedInputOperation<T>,
): ElementTapWaitResult = runInteractionSafely {
    val deadline = clock.nowMs() + timeoutMs
    var observedBlocked = false
    var target: T? = null
    while (target == null) {
        val candidate = operation.lookup()
        val now = clock.nowMs()
        if (candidate?.readiness == ElementInteractionReadiness.READY && now <= deadline) {
            target = candidate.element
        } else {
            observedBlocked = observedBlocked || candidate != null
            if (now >= deadline) {
                return@runInteractionSafely targetTimeout(observedBlocked, timeoutMs, description)
            }
            clock.sleepMs(minOf(INTERACTION_POLL_INTERVAL_MS, deadline - now))
        }
    }

    operation.tap(target)
    while (!operation.hasEditableFocus(target)) {
        val now = clock.nowMs()
        if (now >= deadline) {
            return@runInteractionSafely ElementTapWaitResult(
                success = false,
                error = "Element did not acquire editable focus within ${timeoutMs}ms: $description",
            )
        }
        clock.sleepMs(minOf(INTERACTION_POLL_INTERVAL_MS, deadline - now))
    }
    if (clock.nowMs() > deadline) {
        return@runInteractionSafely ElementTapWaitResult(
            success = false,
            error = "Element did not acquire editable focus within ${timeoutMs}ms: $description",
        )
    }
    operation.input(target)
}

@Suppress("TooGenericExceptionCaught")
private inline fun runInteractionSafely(operation: () -> ElementTapWaitResult): ElementTapWaitResult =
    try {
        operation()
    } catch (error: Exception) {
        ElementTapWaitResult(success = false, error = error.message ?: "Unexpected interaction error")
    }

private fun targetTimeout(
    observedBlocked: Boolean,
    timeoutMs: Long,
    description: String,
) = ElementTapWaitResult(
    success = false,
    error = if (observedBlocked) {
        "Element found but not actionable within ${timeoutMs}ms: $description"
    } else {
        "Element not found within ${timeoutMs}ms: $description"
    },
)
