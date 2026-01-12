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
