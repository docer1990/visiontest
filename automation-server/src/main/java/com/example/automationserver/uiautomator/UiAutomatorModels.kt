package com.example.automationserver.uiautomator

/**
 * Result of UI hierarchy dump operation.
 */
data class UiHierarchyResult(
    val success: Boolean,
    val hierarchy: String? = null,
    val error: String? = null
)

/**
 * Device display and system information.
 */
data class DeviceInfo(
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val displayRotation: Int = 0,
    val productName: String = "",
    val sdkVersion: Int = 0,
    val success: Boolean,
    val error: String? = null
)

/**
 * Generic operation result for simple success/failure operations.
 */
data class OperationResult(
    val success: Boolean,
    val error: String? = null
)

/**
 * Result of element search operation.
 */
data class ElementResult(
    val found: Boolean,
    val text: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val contentDescription: String? = null,
    val bounds: String? = null,
    val isClickable: Boolean? = null,
    val isEnabled: Boolean? = null,
    val error: String? = null
)

/**
 * A single interactive UI element with its properties.
 */
data class InteractiveElement(
    val text: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val contentDescription: String? = null,
    val bounds: String? = null,
    val centerX: Int? = null,
    val centerY: Int? = null,
    val isClickable: Boolean = false,
    val isCheckable: Boolean = false,
    val isScrollable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isEnabled: Boolean = true
)

/**
 * Result of getting interactive elements from the screen.
 */
data class InteractiveElementsResult(
    val success: Boolean,
    val elements: List<InteractiveElement> = emptyList(),
    val count: Int = 0,
    val error: String? = null
)

/**
 * Swipe direction for direction-based swipe operations.
 */
enum class SwipeDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

/**
 * Swipe distance preset.
 */
enum class SwipeDistance {
    SHORT,   // 20% of screen
    MEDIUM,  // 40% of screen (default)
    LONG     // 60% of screen
}

/**
 * Swipe speed preset.
 */
enum class SwipeSpeed {
    SLOW,    // 50 steps
    NORMAL,  // 20 steps (default)
    FAST     // 5 steps
}
