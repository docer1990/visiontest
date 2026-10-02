package com.example.automationserver.uiautomator

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Test
import kotlin.test.assertEquals

class UiObject2AccessibilityTest {
    @Test
    fun `pinned UiObject2 exposes the expected node accessor`() {
        val accessor = resolveUiObject2NodeAccessor()

        assertEquals("getAccessibilityNodeInfo", accessor.name)
        assertEquals(0, accessor.parameterCount)
        assertEquals(AccessibilityNodeInfo::class.java, accessor.returnType)
    }
}
