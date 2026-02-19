package com.example.automationserver.uiautomator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiAutomatorModelsTest {

    // --- UiHierarchyResult ---

    @Test
    fun `UiHierarchyResult success with hierarchy`() {
        val result = UiHierarchyResult(success = true, hierarchy = "<hierarchy/>")
        assertTrue(result.success)
        assertEquals("<hierarchy/>", result.hierarchy)
        assertNull(result.error)
    }

    @Test
    fun `UiHierarchyResult failure with error`() {
        val result = UiHierarchyResult(success = false, error = "timeout")
        assertFalse(result.success)
        assertNull(result.hierarchy)
        assertEquals("timeout", result.error)
    }

    @Test
    fun `UiHierarchyResult defaults hierarchy and error to null`() {
        val result = UiHierarchyResult(success = true)
        assertNull(result.hierarchy)
        assertNull(result.error)
    }

    // --- DeviceInfo ---

    @Test
    fun `DeviceInfo success with all fields`() {
        val info = DeviceInfo(
            displayWidth = 1080,
            displayHeight = 1920,
            displayRotation = 0,
            productName = "Pixel 6",
            sdkVersion = 33,
            success = true
        )
        assertTrue(info.success)
        assertEquals(1080, info.displayWidth)
        assertEquals(1920, info.displayHeight)
        assertEquals(0, info.displayRotation)
        assertEquals("Pixel 6", info.productName)
        assertEquals(33, info.sdkVersion)
        assertNull(info.error)
    }

    @Test
    fun `DeviceInfo defaults to zero and empty values`() {
        val info = DeviceInfo(success = false, error = "no device")
        assertEquals(0, info.displayWidth)
        assertEquals(0, info.displayHeight)
        assertEquals(0, info.displayRotation)
        assertEquals("", info.productName)
        assertEquals(0, info.sdkVersion)
        assertFalse(info.success)
        assertEquals("no device", info.error)
    }

    // --- OperationResult ---

    @Test
    fun `OperationResult success`() {
        val result = OperationResult(success = true)
        assertTrue(result.success)
        assertNull(result.error)
    }

    @Test
    fun `OperationResult failure with error`() {
        val result = OperationResult(success = false, error = "failed to tap")
        assertFalse(result.success)
        assertEquals("failed to tap", result.error)
    }

    // --- ElementResult ---

    @Test
    fun `ElementResult found false with no fields`() {
        val result = ElementResult(found = false)
        assertFalse(result.found)
        assertNull(result.text)
        assertNull(result.resourceId)
        assertNull(result.className)
        assertNull(result.contentDescription)
        assertNull(result.bounds)
        assertNull(result.isClickable)
        assertNull(result.isEnabled)
        assertNull(result.error)
    }

    @Test
    fun `ElementResult found true with all fields`() {
        val result = ElementResult(
            found = true,
            text = "Submit",
            resourceId = "com.app:id/submit_btn",
            className = "android.widget.Button",
            contentDescription = "Submit form",
            bounds = "[0,0][100,50]",
            isClickable = true,
            isEnabled = true
        )
        assertTrue(result.found)
        assertEquals("Submit", result.text)
        assertEquals("com.app:id/submit_btn", result.resourceId)
        assertEquals("android.widget.Button", result.className)
        assertEquals("Submit form", result.contentDescription)
        assertEquals("[0,0][100,50]", result.bounds)
        assertEquals(true, result.isClickable)
        assertEquals(true, result.isEnabled)
        assertNull(result.error)
    }

    @Test
    fun `ElementResult with error`() {
        val result = ElementResult(found = false, error = "timeout searching")
        assertFalse(result.found)
        assertEquals("timeout searching", result.error)
    }

    // --- InteractiveElement ---

    @Test
    fun `InteractiveElement default isClickable is false`() {
        val element = InteractiveElement()
        assertFalse(element.isClickable)
    }

    @Test
    fun `InteractiveElement default isCheckable is false`() {
        val element = InteractiveElement()
        assertFalse(element.isCheckable)
    }

    @Test
    fun `InteractiveElement default isScrollable is false`() {
        val element = InteractiveElement()
        assertFalse(element.isScrollable)
    }

    @Test
    fun `InteractiveElement default isLongClickable is false`() {
        val element = InteractiveElement()
        assertFalse(element.isLongClickable)
    }

    @Test
    fun `InteractiveElement default isEnabled is true`() {
        val element = InteractiveElement()
        assertTrue(element.isEnabled)
    }

    @Test
    fun `InteractiveElement defaults optional fields to null`() {
        val element = InteractiveElement()
        assertNull(element.text)
        assertNull(element.resourceId)
        assertNull(element.className)
        assertNull(element.contentDescription)
        assertNull(element.bounds)
        assertNull(element.centerX)
        assertNull(element.centerY)
    }

    @Test
    fun `InteractiveElement with all fields set`() {
        val element = InteractiveElement(
            text = "OK",
            resourceId = "com.app:id/ok",
            className = "android.widget.Button",
            contentDescription = "Confirm",
            bounds = "[10,20][90,80]",
            centerX = 50,
            centerY = 50,
            isClickable = true,
            isCheckable = false,
            isScrollable = false,
            isLongClickable = true,
            isEnabled = true
        )
        assertEquals("OK", element.text)
        assertEquals("com.app:id/ok", element.resourceId)
        assertEquals("android.widget.Button", element.className)
        assertEquals("Confirm", element.contentDescription)
        assertEquals("[10,20][90,80]", element.bounds)
        assertEquals(50, element.centerX)
        assertEquals(50, element.centerY)
        assertTrue(element.isClickable)
        assertFalse(element.isCheckable)
        assertFalse(element.isScrollable)
        assertTrue(element.isLongClickable)
        assertTrue(element.isEnabled)
    }

    // --- InteractiveElementsResult ---

    @Test
    fun `InteractiveElementsResult empty elements`() {
        val result = InteractiveElementsResult(success = true)
        assertTrue(result.success)
        assertTrue(result.elements.isEmpty())
        assertEquals(0, result.count)
        assertNull(result.error)
    }

    @Test
    fun `InteractiveElementsResult with elements and matching count`() {
        val elements = listOf(
            InteractiveElement(text = "A", centerX = 10, centerY = 20),
            InteractiveElement(text = "B", centerX = 30, centerY = 40)
        )
        val result = InteractiveElementsResult(
            success = true,
            elements = elements,
            count = 2
        )
        assertTrue(result.success)
        assertEquals(2, result.elements.size)
        assertEquals(2, result.count)
        assertEquals("A", result.elements[0].text)
        assertEquals("B", result.elements[1].text)
    }

    @Test
    fun `InteractiveElementsResult failure with error`() {
        val result = InteractiveElementsResult(success = false, error = "no hierarchy")
        assertFalse(result.success)
        assertTrue(result.elements.isEmpty())
        assertEquals("no hierarchy", result.error)
    }

    // --- SwipeSpeed ---

    @Test
    fun `SwipeSpeed SLOW has 3 values in enum`() {
        assertEquals(3, SwipeSpeed.entries.size)
    }

    @Test
    fun `SwipeSpeed values are SLOW NORMAL FAST`() {
        val values = SwipeSpeed.entries.map { it.name }
        assertEquals(listOf("SLOW", "NORMAL", "FAST"), values)
    }

    // --- SwipeDirection ---

    @Test
    fun `SwipeDirection has UP DOWN LEFT RIGHT`() {
        val values = SwipeDirection.entries.map { it.name }
        assertEquals(listOf("UP", "DOWN", "LEFT", "RIGHT"), values)
    }

    @Test
    fun `SwipeDirection has exactly 4 values`() {
        assertEquals(4, SwipeDirection.entries.size)
    }

    // --- SwipeDistance ---

    @Test
    fun `SwipeDistance has SHORT MEDIUM LONG`() {
        val values = SwipeDistance.entries.map { it.name }
        assertEquals(listOf("SHORT", "MEDIUM", "LONG"), values)
    }

    @Test
    fun `SwipeDistance has exactly 3 values`() {
        assertEquals(3, SwipeDistance.entries.size)
    }
}
