package com.example.automationserver.uiautomator

import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        assertTrue(result.error!!.contains("editable"))
        verify(exactly = 0) { node.performAction(any(), any()) }
        verify(exactly = 1) { node.recycle() }
    }
}
