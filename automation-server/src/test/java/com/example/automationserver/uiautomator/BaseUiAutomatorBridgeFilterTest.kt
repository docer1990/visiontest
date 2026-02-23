package com.example.automationserver.uiautomator

import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiDevice
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class BaseUiAutomatorBridgeFilterTest {

    private val displayRect = Rect(0, 0, 1080, 1920)

    private val bridge = object : BaseUiAutomatorBridge() {
        override fun getUiDevice(): UiDevice = mockk()
        override fun getUiAutomation(): UiAutomation = mockk()
        override fun getDisplayRect(): Rect = displayRect
    }

    private val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()

    @Suppress("DEPRECATION")
    private fun createNode(
        className: String = "android.view.View",
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        isVisibleToUser: Boolean = true,
        isEnabled: Boolean = true,
        isClickable: Boolean = false,
        isCheckable: Boolean = false,
        isScrollable: Boolean = false,
        isLongClickable: Boolean = false,
        bounds: Rect = Rect(100, 100, 200, 200)
    ): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain()
        node.className = className
        text?.let { node.text = it }
        contentDescription?.let { node.contentDescription = it }
        resourceId?.let { node.viewIdResourceName = it }
        node.isVisibleToUser = isVisibleToUser
        node.isEnabled = isEnabled
        node.isClickable = isClickable
        node.isCheckable = isCheckable
        node.isScrollable = isScrollable
        node.isLongClickable = isLongClickable
        node.setBoundsInScreen(bounds)
        nodesToRecycle.add(node)
        return node
    }

    @Before
    fun setUp() {
        nodesToRecycle.clear()
    }

    @Suppress("DEPRECATION")
    @After
    fun tearDown() {
        nodesToRecycle.forEach { it.recycle() }
    }

    // --- Visibility filtering ---

    @Test
    fun `not visible to user returns null`() {
        val node = createNode(isVisibleToUser = false)
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `visible to user proceeds to further checks`() {
        val node = createNode(isClickable = true)
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    // --- Enabled filtering ---

    @Test
    fun `disabled element excluded when includeDisabled is false`() {
        val node = createNode(isEnabled = false, isClickable = true)
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `disabled element included when includeDisabled is true`() {
        val node = createNode(isEnabled = false, isClickable = true)
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = true))
    }

    // --- Bounds filtering ---

    @Test
    fun `empty bounds width returns null`() {
        val node = createNode(isClickable = true, bounds = Rect(100, 100, 100, 200))
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `empty bounds height returns null`() {
        val node = createNode(isClickable = true, bounds = Rect(100, 100, 200, 100))
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `node outside display rect returns null`() {
        val node = createNode(isClickable = true, bounds = Rect(2000, 2000, 2100, 2100))
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    // --- Interactive property flags ---

    @Test
    fun `clickable node returns bounds`() {
        val node = createNode(isClickable = true)
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `checkable node returns bounds`() {
        val node = createNode(isCheckable = true)
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `scrollable node returns bounds`() {
        val node = createNode(isScrollable = true)
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `long-clickable node returns bounds`() {
        val node = createNode(isLongClickable = true)
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    // --- Known interactive classes ---

    @Test
    fun `Button class returns bounds`() {
        val node = createNode(className = "android.widget.Button")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `EditText class returns bounds`() {
        val node = createNode(className = "android.widget.EditText")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `MaterialButton class returns bounds`() {
        val node = createNode(className = "com.google.android.material.button.MaterialButton")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `CheckBox class returns bounds`() {
        val node = createNode(className = "android.widget.CheckBox")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `Switch class returns bounds`() {
        val node = createNode(className = "android.widget.Switch")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    // --- Layout containers ---

    @Test
    fun `FrameLayout without content returns null`() {
        val node = createNode(className = "android.widget.FrameLayout")
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `LinearLayout without content returns null`() {
        val node = createNode(className = "android.widget.LinearLayout")
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `FrameLayout with text returns bounds`() {
        val node = createNode(className = "android.widget.FrameLayout", text = "Hello")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `FrameLayout with contentDescription returns bounds`() {
        val node = createNode(className = "android.widget.FrameLayout", contentDescription = "Description")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `FrameLayout with resourceId returns bounds`() {
        val node = createNode(className = "android.widget.FrameLayout", resourceId = "com.example:id/container")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    // --- TextView ---

    @Test
    fun `TextView with empty text returns null`() {
        val node = createNode(className = "android.widget.TextView")
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `TextView with text returns bounds`() {
        val node = createNode(className = "android.widget.TextView", text = "Hello")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `TextView with contentDescription returns bounds`() {
        val node = createNode(className = "android.widget.TextView", contentDescription = "Label")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `TextView with resourceId returns bounds`() {
        val node = createNode(className = "android.widget.TextView", resourceId = "com.example:id/label")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    // --- ImageView ---

    @Test
    fun `ImageView without content returns null`() {
        val node = createNode(className = "android.widget.ImageView")
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `ImageView with contentDescription returns bounds`() {
        val node = createNode(className = "android.widget.ImageView", contentDescription = "Icon")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    // --- Generic View with meaningful content ---

    @Test
    fun `View with text returns bounds`() {
        val node = createNode(className = "android.view.View", text = "Content")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `View with contentDescription returns bounds`() {
        val node = createNode(className = "android.view.View", contentDescription = "Description")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `View with only resourceId returns bounds`() {
        val node = createNode(className = "android.view.View", resourceId = "com.example:id/view")
        assertNotNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

    @Test
    fun `View without any content returns null`() {
        val node = createNode(className = "android.view.View")
        assertNull(bridge.getIncludedElementBounds(node, displayRect, includeDisabled = false))
    }

}
