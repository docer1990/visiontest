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
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidInteractionActionsTest {
    private val device = mockk<UiDevice>()
    private val automation = mockk<UiAutomation>()
    private val selector = mockk<BySelector>()

    @Test
    fun `targeted input taps focuses and sets text on a custom editable element`() {
        val element = readyElement(className = "example.CustomInput")
        val focusedNode = editableFocusedNode(inputAccepted = true)
        every { element.click() } returns Unit
        every { element.isFocused } returns true
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedNode
        every { device.findObject(selector) } returns element

        val result = actions { candidate ->
            assertSame(element, candidate)
            true
        }.targetedInput(targetedInputRequest())

        assertTrue(result.success)
        verify(exactly = 1) { element.click() }
        verify(exactly = 1) {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any())
        }
        verify(exactly = 1) { focusedNode.recycle() }
    }

    @Test
    fun `targeted input does not tap a custom noneditable selected element`() {
        val element = readyElement(className = "example.CustomInput")
        every { device.findObject(selector) } returns element

        val result = actions { false }.targetedInput(targetedInputRequest())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("not actionable"))
        verify(exactly = 0) { element.click() }
    }

    @Test
    fun `targeted input propagates an editability failure before tapping`() {
        val element = readyElement(className = "example.CustomInput")
        val failure = IllegalStateException("node unavailable")
        every { device.findObject(selector) } returns element

        val result = actions { throw failure }.targetedInput(targetedInputRequest())

        assertFalse(result.success)
        assertEquals("node unavailable", result.error)
        verify(exactly = 0) { element.click() }
    }

    @Test
    fun `targeted input rejects a focused noneditable element`() {
        val element = readyElement()
        val focusedNode = editableFocusedNode(inputAccepted = true, editable = false)
        every { element.click() } returns Unit
        every { element.isFocused } returns true
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedNode
        every { device.findObject(selector) } returns element

        val result = actions { true }.targetedInput(targetedInputRequest())

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
        every { element.click() } returns Unit
        every { element.isFocused } returns true
        every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedNode
        every { device.findObject(selector) } returns element

        val result = actions { true }.targetedInput(targetedInputRequest())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("rejected"))
        verify(exactly = 1) { focusedNode.recycle() }
    }

    private fun actions(isEditable: (UiObject2) -> Boolean) = AndroidInteractionActions(
        device = device,
        automation = automation,
        displayRect = Rect(0, 0, 1080, 1920),
        selectorBuilder = { selector },
        selectorDescription = { "resourceId=name" },
        isEditable = isEditable,
    )

    private fun readyElement(
        className: String = "android.widget.EditText",
    ): UiObject2 = mockk<UiObject2>().also { element ->
        every { element.isEnabled } returns true
        every { element.isFocusable } returns true
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
}
