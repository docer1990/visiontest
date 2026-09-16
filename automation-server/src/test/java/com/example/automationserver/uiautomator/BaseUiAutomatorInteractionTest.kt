package com.example.automationserver.uiautomator

import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BaseUiAutomatorInteractionTest {
    private val device = mockk<UiDevice>()
    private val automation = mockk<UiAutomation>()
    private val bridge = object : BaseUiAutomatorBridge() {
        override fun getUiDevice(): UiDevice = device
        override fun getUiAutomation(): UiAutomation = automation
        override fun getDisplayRect(): Rect = Rect(0, 0, 1080, 1920)
    }

    @Test
    fun `pressKey reports the native device result`() {
        every { device.pressKeyCode(66) } returns true

        assertTrue(bridge.pressKey(66).success)

        verify(exactly = 1) { device.pressKeyCode(66) }
    }

    @Test
    fun `pressKey reports a rejected native key action`() {
        every { device.pressKeyCode(66) } returns false

        val result = bridge.pressKey(66)

        assertFalse(result.success)
        assertEquals("Native key press failed", result.error)
    }

    @Test
    fun `clearText clears a focused editable node`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node
        every { node.isEditable } returns true
        every { node.isEnabled } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true
        every { node.recycle() } returns Unit

        assertTrue(bridge.clearText().success)

        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun `clearText rejects a focused noneditable node`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node
        every { node.isEditable } returns false
        every { node.isEnabled } returns true
        every { node.recycle() } returns Unit

        val result = bridge.clearText()

        assertFalse(result.success)
        assertEquals("Focused element is not editable", result.error)
        verify(exactly = 0) { node.performAction(any(), any()) }
        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun `clearText rejects a focused disabled node`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node
        every { node.isEditable } returns true
        every { node.isEnabled } returns false
        every { node.recycle() } returns Unit

        val result = bridge.clearText()

        assertFalse(result.success)
        assertEquals("Focused element is not enabled", result.error)
        verify(exactly = 0) { node.performAction(any(), any()) }
        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun `coordinate longPress holds the point for eight hundred milliseconds`() {
        every { device.swipe(10, 20, 10, 20, 160) } returns true

        val result = bridge.longPress(
            NativeGestureRequest(NativeGestureTarget.Coordinates(10, 20))
        )

        assertTrue(result.success)
        verify(exactly = 1) { device.swipe(10, 20, 10, 20, 160) }
    }

    @Test
    fun `element longPress acts on the ready native element`() {
        val element = readyElement()
        every { device.findObject(any<BySelector>()) } returns element
        every { element.click(800L) } returns Unit

        val result = bridge.longPress(elementRequest("menu"))

        assertTrue(result.success)
        verify(exactly = 1) { element.click(800L) }
    }

    @Test
    fun `coordinate doubleTap injects two taps`() {
        every { device.click(30, 40) } returns true

        val result = bridge.doubleTap(
            NativeGestureRequest(NativeGestureTarget.Coordinates(30, 40))
        )

        assertTrue(result.success)
        verify(exactly = 2) { device.click(30, 40) }
    }

    @Test
    fun `element doubleTap injects two taps at the retained element center`() {
        val element = readyElement()
        every { device.findObject(any<BySelector>()) } returns element
        every { device.click(150, 150) } returns true

        val result = bridge.doubleTap(elementRequest("menu"))

        assertTrue(result.success)
        verify(exactly = 2) { device.click(150, 150) }
        verify(exactly = 0) { element.click() }
    }

    @Test
    fun `targeted input taps focuses and sets text on one element`() {
        val element = readyElement()
        val focusedNode = editableFocusedNode(inputAccepted = true)
        every { element.isFocusable } returns true
        every { element.click() } returns Unit
        every { element.isFocused } returns true
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedNode
        every { device.findObject(any<BySelector>()) } returns element

        val result = bridge.inputText(
            TargetedInputRequest(
                text = "Ada",
                selectors = TapOnElementSelectors(resourceId = "name"),
                timeoutMs = 1_000,
            )
        )

        assertTrue(result.success)
        verify(exactly = 1) { element.click() }
        verify(exactly = 1) {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any())
        }
        verify(exactly = 1) { focusedNode.recycle() }
    }

    @Test
    fun `targeted input does not tap a noneditable selected element`() {
        val element = readyElement(className = "android.widget.Button")
        every { element.isFocusable } returns true
        every { device.findObject(any<BySelector>()) } returns element

        val result = bridge.inputText(targetedInputRequest())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("not actionable"))
        verify(exactly = 0) { element.click() }
    }

    @Test
    fun `targeted input rejects a focused noneditable element`() {
        val element = readyElement()
        val focusedNode = editableFocusedNode(inputAccepted = true, editable = false)
        every { element.isFocusable } returns true
        every { element.click() } returns Unit
        every { element.isFocused } returns true
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedNode
        every { device.findObject(any<BySelector>()) } returns element

        val result = bridge.inputText(targetedInputRequest())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("not editable"))
        verify(exactly = 0) {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any())
        }
        verify(exactly = 1) { focusedNode.recycle() }
    }

    @Test
    fun `targeted input reports a rejected native text action`() {
        val element = readyElement()
        val focusedNode = editableFocusedNode(inputAccepted = false)
        every { element.isFocusable } returns true
        every { element.click() } returns Unit
        every { element.isFocused } returns true
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedNode
        every { device.findObject(any<BySelector>()) } returns element

        val result = bridge.inputText(targetedInputRequest())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("rejected"))
        verify(exactly = 1) { focusedNode.recycle() }
    }

    private fun readyElement(className: String = "android.widget.EditText"): UiObject2 = mockk<UiObject2>().also { element ->
        every { element.isEnabled } returns true
        every { element.visibleBounds } returns Rect(100, 100, 200, 200)
        every { element.className } returns className
    }

    private fun editableFocusedNode(
        inputAccepted: Boolean,
        editable: Boolean = true,
    ): AccessibilityNodeInfo = mockk<AccessibilityNodeInfo>().also { node ->
        every { node.isEditable } returns editable
        every { node.isEnabled } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns inputAccepted
        every { node.recycle() } returns Unit
    }

    private fun targetedInputRequest() = TargetedInputRequest(
        text = "Ada",
        selectors = TapOnElementSelectors(resourceId = "name"),
        timeoutMs = 1_000,
    )

    private fun elementRequest(resourceId: String) = NativeGestureRequest(
        NativeGestureTarget.Element(
            selectors = TapOnElementSelectors(resourceId = resourceId),
            timeoutMs = 1_000,
        )
    )
}
