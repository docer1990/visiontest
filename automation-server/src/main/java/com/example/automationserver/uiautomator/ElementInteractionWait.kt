package com.example.automationserver.uiautomator

import com.example.automationserver.performance.NativeTimingRecorder

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
        val candidate = NativeTimingRecorder.pollAttempt { lookup() }
        val now = clock.nowMs()
        if (candidate?.readiness == ElementInteractionReadiness.READY && now <= deadline) {
            interact(candidate.element)
            result = ElementTapWaitResult(success = true)
        } else {
            observedBlocked = observedBlocked || candidate != null
            if (now >= deadline) {
                result = targetTimeout(observedBlocked, timeoutMs, description)
            } else {
                NativeTimingRecorder.pollWait { clock.sleepMs(minOf(INTERACTION_POLL_INTERVAL_MS, deadline - now)) }
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
        val candidate = NativeTimingRecorder.pollAttempt { operation.lookup() }
        val now = clock.nowMs()
        if (candidate?.readiness == ElementInteractionReadiness.READY && now <= deadline) {
            target = candidate.element
        } else {
            observedBlocked = observedBlocked || candidate != null
            if (now >= deadline) {
                return@runInteractionSafely targetTimeout(observedBlocked, timeoutMs, description)
            }
            NativeTimingRecorder.pollWait { clock.sleepMs(minOf(INTERACTION_POLL_INTERVAL_MS, deadline - now)) }
        }
    }

    operation.tap(target)
    while (!NativeTimingRecorder.pollAttempt { operation.hasEditableFocus(target) }) {
        val now = clock.nowMs()
        if (now >= deadline) {
            return@runInteractionSafely ElementTapWaitResult(
                success = false,
                error = "Element did not acquire editable focus within ${timeoutMs}ms: $description",
            )
        }
        NativeTimingRecorder.pollWait { clock.sleepMs(minOf(INTERACTION_POLL_INTERVAL_MS, deadline - now)) }
    }
    if (clock.nowMs() > deadline) {
        return@runInteractionSafely ElementTapWaitResult(
            success = false,
            error = "Element did not acquire editable focus within ${timeoutMs}ms: $description",
        )
    }
    val inputResult = operation.input(target)
    if (!inputResult.success) {
        inputResult
    } else if (clock.nowMs() > deadline) {
        ElementTapWaitResult(
            success = false,
            error = "Text input exceeded the original timeout; input within ${timeoutMs}ms was required: $description",
        )
    } else {
        inputResult
    }
}

@Suppress("TooGenericExceptionCaught")
private fun runInteractionSafely(operation: () -> ElementTapWaitResult): ElementTapWaitResult =
    try {
        NativeTimingRecorder.measureCurrent("poll") { operation() }
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
