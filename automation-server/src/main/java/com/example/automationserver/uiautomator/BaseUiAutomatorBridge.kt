package com.example.automationserver.uiautomator

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Abstract base class for UIAutomator operations.
 *
 * This class provides the common implementation of UIAutomator operations.
 * Subclasses must provide properly initialized instances via abstract methods.
 *
 * ## Why this abstraction exists
 *
 * Android's UIAutomator requires a valid [android.app.Instrumentation] context to function.
 * This base class encapsulates all UIAutomator operations, allowing different subclasses
 * to provide the required instances in whatever way is appropriate for their context.
 *
 * ## Hierarchy Dumping
 *
 * Uses reflection to call `UiDevice.getWindowRoots()` - a private method that returns
 * all accessibility window roots. This approach is used by Maestro and works reliably
 * with Flutter and other frameworks that expose accessibility nodes.
 *
 * @see UiAutomatorBridgeInstrumented for the instrumentation-based implementation
 */
abstract class BaseUiAutomatorBridge {

    companion object {
        private const val TAG = "BaseUiAutomatorBridge"
        private const val WEBVIEW_CLASS_NAME = "android.webkit.WebView"
    }

    /**
     * Returns the UiDevice instance to use for operations.
     * Subclasses must implement this to provide a properly initialized UiDevice.
     */
    protected abstract fun getUiDevice(): UiDevice

    /**
     * Returns the UiAutomation instance for direct accessibility tree access.
     * Subclasses must implement this to provide access to UiAutomation.
     */
    protected abstract fun getUiAutomation(): UiAutomation

    /**
     * Returns the display bounds for the device.
     * Subclasses must implement this to provide the display rect.
     */
    protected abstract fun getDisplayRect(): Rect

    /**
     * Dumps the current UI hierarchy as XML.
     *
     * Uses reflection to call `UiDevice.getWindowRoots()` which provides access
     * to all accessibility window roots, including those from Flutter apps.
     *
     * @return [UiHierarchyResult] containing the XML hierarchy or error information
     */
    fun dumpHierarchy(): UiHierarchyResult {
        return try {
            val device = getUiDevice()
            val uiAutomation = getUiAutomation()

            device.waitForIdle()

            val outputStream = ByteArrayOutputStream()
            dumpHierarchyInternal(device, uiAutomation, outputStream)
            val xmlHierarchy = outputStream.toString("UTF-8")

            Log.d(TAG, "Hierarchy dump size: ${xmlHierarchy.length} bytes")

            UiHierarchyResult(
                success = true,
                hierarchy = xmlHierarchy,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping hierarchy", e)
            UiHierarchyResult(
                success = false,
                hierarchy = null,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Dumps hierarchy using reflection to get window roots, similar to Maestro's approach.
     *
     * Note: This uses reflection to access a private method, which is fragile but necessary
     * for proper Flutter app support. If the reflection fails, it falls back to
     * [UiAutomation.rootInActiveWindow].
     */
    private fun dumpHierarchyInternal(
        device: UiDevice,
        uiAutomation: UiAutomation,
        out: OutputStream
    ) {
        val displayRect = getDisplayRect()

        val serializer = Xml.newSerializer()
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag("", "hierarchy")
        serializer.attribute("", "rotation", device.displayRotation.toString())

        // Use reflection to get window roots, like Maestro does
        val roots = try {
            device.javaClass
                .getDeclaredMethod("getWindowRoots")
                .apply { isAccessible = true }
                .let {
                    @Suppress("UNCHECKED_CAST")
                    it.invoke(device) as Array<AccessibilityNodeInfo>
                }
                .toList()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to call getWindowRoots via reflection, falling back", e)
            listOfNotNull(uiAutomation.rootInActiveWindow)
        }

        Log.d(TAG, "Found ${roots.size} window roots")

        roots.forEach { root ->
            dumpNodeRecursive(root, serializer, 0, displayRect, insideWebView = false)
            root.recycle()
        }

        serializer.endTag("", "hierarchy")
        serializer.endDocument()
    }

    /**
     * Recursively dumps an AccessibilityNodeInfo to XML.
     *
     * @param node The accessibility node to dump
     * @param serializer The XML serializer to write to
     * @param index The index of this node among its siblings
     * @param displayRect The display bounds for visibility calculations
     * @param insideWebView Whether this node is inside a WebView (affects visibility filtering)
     */
    private fun dumpNodeRecursive(
        node: AccessibilityNodeInfo,
        serializer: org.xmlpull.v1.XmlSerializer,
        index: Int,
        displayRect: Rect,
        insideWebView: Boolean
    ) {
        serializer.startTag("", "node")
        serializer.attribute("", "index", index.toString())
        serializer.attribute("", "text", safeCharSeqToString(node.text))
        serializer.attribute("", "resource-id", safeCharSeqToString(node.viewIdResourceName))
        serializer.attribute("", "class", safeCharSeqToString(node.className))
        serializer.attribute("", "package", safeCharSeqToString(node.packageName))
        serializer.attribute("", "content-desc", safeCharSeqToString(node.contentDescription))
        serializer.attribute("", "checkable", node.isCheckable.toString())
        serializer.attribute("", "checked", node.isChecked.toString())
        serializer.attribute("", "clickable", node.isClickable.toString())
        serializer.attribute("", "enabled", node.isEnabled.toString())
        serializer.attribute("", "focusable", node.isFocusable.toString())
        serializer.attribute("", "focused", node.isFocused.toString())
        serializer.attribute("", "scrollable", node.isScrollable.toString())
        serializer.attribute("", "long-clickable", node.isLongClickable.toString())
        serializer.attribute("", "password", node.isPassword.toString())
        serializer.attribute("", "selected", node.isSelected.toString())
        serializer.attribute("", "bounds", getVisibleBoundsInScreen(node, displayRect)?.toShortString() ?: "")

        val childCount = node.childCount
        val isWebView = node.className?.toString() == WEBVIEW_CLASS_NAME

        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                // WebView contents sometimes report as invisible but should still be dumped
                // This is a workaround for a known Android accessibility bug
                if (child.isVisibleToUser || insideWebView || isWebView) {
                    dumpNodeRecursive(child, serializer, i, displayRect, insideWebView || isWebView)
                }
                child.recycle()
            }
        }

        serializer.endTag("", "node")
    }

    private fun safeCharSeqToString(cs: CharSequence?): String {
        return cs?.let { stripInvalidXMLChars(it) } ?: ""
    }

    /**
     * Strips invalid XML characters from a CharSequence.
     *
     * Based on XML 1.1 specification character ranges.
     * See: http://www.w3.org/TR/xml11/#charsets
     */
    @Suppress("ComplexCondition")
    private fun stripInvalidXMLChars(cs: CharSequence): String {
        val ret = StringBuilder()
        for (i in cs.indices) {
            val ch = cs[i]
            val code = ch.code
            // Invalid XML character ranges per XML 1.1 spec
            if (code in 0x1..0x8 ||
                code in 0xB..0xC ||
                code in 0xE..0x1F ||
                code in 0x7F..0x84 ||
                code in 0x86..0x9F ||
                code in 0xFDD0..0xFDDF ||
                code in 0x1FFFE..0x1FFFF ||
                code in 0x2FFFE..0x2FFFF ||
                code in 0x3FFFE..0x3FFFF ||
                code in 0x4FFFE..0x4FFFF ||
                code in 0x5FFFE..0x5FFFF ||
                code in 0x6FFFE..0x6FFFF ||
                code in 0x7FFFE..0x7FFFF ||
                code in 0x8FFFE..0x8FFFF ||
                code in 0x9FFFE..0x9FFFF ||
                code in 0xAFFFE..0xAFFFF ||
                code in 0xBFFFE..0xBFFFF ||
                code in 0xCFFFE..0xCFFFF ||
                code in 0xDFFFE..0xDFFFF ||
                code in 0xEFFFE..0xEFFFF ||
                code in 0xFFFFE..0xFFFFF ||
                code in 0x10FFFE..0x10FFFF
            ) {
                ret.append(".")
            } else {
                ret.append(ch)
            }
        }
        return ret.toString()
    }

    private fun getVisibleBoundsInScreen(node: AccessibilityNodeInfo?, displayRect: Rect): Rect? {
        if (node == null) return null
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        return if (nodeRect.intersect(displayRect)) nodeRect else Rect()
    }

    /**
     * Gets device display and system information.
     *
     * @return [DeviceInfo] containing display dimensions, rotation, and system info
     */
    fun getDeviceInfo(): DeviceInfo {
        return try {
            val device = getUiDevice()
            DeviceInfo(
                displayWidth = device.displayWidth,
                displayHeight = device.displayHeight,
                displayRotation = device.displayRotation,
                productName = Build.PRODUCT,
                sdkVersion = Build.VERSION.SDK_INT,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device info", e)
            DeviceInfo(success = false, error = e.message)
        }
    }

    /**
     * Performs a click at the given screen coordinates.
     *
     * @param x The x coordinate in pixels
     * @param y The y coordinate in pixels
     * @return [OperationResult] indicating success or failure
     */
    fun click(x: Int, y: Int): OperationResult {
        return try {
            val success = getUiDevice().click(x, y)
            OperationResult(success = success)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click at ($x, $y)", e)
            OperationResult(success = false, error = e.message)
        }
    }

    /**
     * Presses the device back button.
     *
     * @return [OperationResult] indicating success or failure
     */
    fun pressBack(): OperationResult {
        return try {
            val success = getUiDevice().pressBack()
            OperationResult(success = success)
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing back", e)
            OperationResult(success = false, error = e.message)
        }
    }

    /**
     * Presses the device home button.
     *
     * @return [OperationResult] indicating success or failure
     */
    fun pressHome(): OperationResult {
        return try {
            val success = getUiDevice().pressHome()
            OperationResult(success = success)
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing home", e)
            OperationResult(success = false, error = e.message)
        }
    }

    /**
     * Finds a UI element using various selectors.
     *
     * Provide at least one selector parameter. If multiple are provided,
     * only the first non-null selector is used (in order: text, textContains,
     * resourceId, className, contentDescription).
     *
     * @param text Exact text match
     * @param textContains Partial text match (contains)
     * @param resourceId Resource ID (e.g., "com.example:id/button")
     * @param className View class name (e.g., "android.widget.Button")
     * @param contentDescription Accessibility content description
     * @return [ElementResult] with element info if found, or found=false if not
     */
    fun findElement(
        text: String? = null,
        textContains: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDescription: String? = null
    ): ElementResult {
        return try {
            val selector = when {
                text != null -> By.text(text)
                textContains != null -> By.textContains(textContains)
                resourceId != null -> By.res(resourceId)
                className != null -> By.clazz(className)
                contentDescription != null -> By.desc(contentDescription)
                else -> return ElementResult(found = false, error = "No selector provided")
            }

            val element = getUiDevice().findObject(selector)
            if (element != null) {
                ElementResult(
                    found = true,
                    text = element.text,
                    resourceId = element.resourceName,
                    className = element.className,
                    contentDescription = element.contentDescription,
                    bounds = element.visibleBounds?.toString(),
                    isClickable = element.isClickable,
                    isEnabled = element.isEnabled
                )
            } else {
                ElementResult(found = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding element", e)
            ElementResult(found = false, error = e.message)
        }
    }
}
