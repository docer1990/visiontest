package com.example.automationserver.uiautomator

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiObject2
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

private val uiObject2NodeAccessor: Method by lazy(::resolveUiObject2NodeAccessor)

@Suppress("TooGenericExceptionCaught")
internal fun resolveUiObject2NodeAccessor(): Method = try {
    UiObject2::class.java.getDeclaredMethod("getAccessibilityNodeInfo").apply {
        check(returnType == AccessibilityNodeInfo::class.java) {
            "UiObject2 node accessor has an unexpected return type"
        }
        isAccessible = true
    }
} catch (error: Exception) {
    throw IllegalStateException("Cannot access the selected UiObject2 accessibility node", error)
}

@Suppress("TooGenericExceptionCaught")
internal fun UiObject2.isAccessibilityEditable(): Boolean = try {
    // UiObject2 owns this node, so the caller must not recycle it.
    val node = uiObject2NodeAccessor.invoke(this) as AccessibilityNodeInfo
    node.isEditable
} catch (error: InvocationTargetException) {
    val cause = error.cause ?: error
    if (cause is RuntimeException) throw cause
    throw IllegalStateException("Cannot read the selected UiObject2 accessibility node", cause)
} catch (error: Exception) {
    throw IllegalStateException("Cannot read the selected UiObject2 accessibility node", error)
}
