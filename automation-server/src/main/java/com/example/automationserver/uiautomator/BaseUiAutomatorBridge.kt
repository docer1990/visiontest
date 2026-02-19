package com.example.automationserver.uiautomator

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
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

        // Known interactive class names (even if clickable=false)
        private val INTERACTIVE_CLASSES = setOf(
            "android.widget.Button",
            "android.widget.ImageButton",
            "com.google.android.material.button.MaterialButton",
            "com.google.android.material.floatingactionbutton.FloatingActionButton",
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.CheckBox",
            "android.widget.Switch",
            "androidx.appcompat.widget.SwitchCompat",
            "android.widget.RadioButton",
            "android.widget.ToggleButton",
            "android.widget.Spinner",
            "android.widget.SeekBar",
            "android.widget.RatingBar"
        )

        // Short class names for interactive classes, pre-computed once for efficient lookup
        private val INTERACTIVE_CLASS_SHORT_NAMES = INTERACTIVE_CLASSES.map { it.substringAfterLast('.') }.toSet()

        // Layout containers to exclude (unless they have text, content-desc, or resource-id)
        // Note: android.view.View is intentionally excluded from this list because
        // Flutter, Compose, and other frameworks use it for meaningful interactive nodes.
        private val LAYOUT_CONTAINERS = setOf(
            "android.widget.FrameLayout",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
            "android.view.ViewGroup",
            "androidx.constraintlayout.widget.ConstraintLayout",
            "androidx.coordinatorlayout.widget.CoordinatorLayout",
            "androidx.appcompat.widget.LinearLayoutCompat"
        )

        private fun SwipeSpeed.toSteps(): Int = when (this) {
            SwipeSpeed.SLOW -> 50
            SwipeSpeed.NORMAL -> 20
            SwipeSpeed.FAST -> 5
        }
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

    private fun getVisibleBoundsInScreen(node: AccessibilityNodeInfo?, displayRect: Rect): Rect? {
        if (node == null) return null
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        return if (nodeRect.intersect(displayRect)) nodeRect else null
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
     * Performs a tap at the given screen coordinates.
     *
     * @param x The x coordinate in pixels
     * @param y The y coordinate in pixels
     * @return [OperationResult] indicating success or failure
     */
    fun tapByCoordinates(x: Int, y: Int): OperationResult {
        return try {
            val device = getUiDevice()
            Log.d(TAG, "Waiting for idle before click at ($x, $y)")
            device.waitForIdle(1000)
            Log.d(TAG, "Performing click at ($x, $y)")
            val success = device.click(x, y)
            Log.d(TAG, "Click completed with success=$success")
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

    /**
     * Builds a [BySelector] by chaining all non-null selector criteria.
     * When multiple selectors are provided, they are combined (AND logic)
     * to create a more specific match.
     *
     * @return A combined [BySelector], or null if no criteria were provided.
     */
    private fun buildSelector(
        text: String? = null,
        textContains: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDescription: String? = null
    ): BySelector? {
        var selector: BySelector? = null
        text?.let { selector = (selector?.text(it) ?: By.text(it)) }
        textContains?.let { selector = (selector?.textContains(it) ?: By.textContains(it)) }
        resourceId?.let { selector = (selector?.res(it) ?: By.res(it)) }
        className?.let { selector = (selector?.clazz(it) ?: By.clazz(it)) }
        contentDescription?.let { selector = (selector?.desc(it) ?: By.desc(it)) }
        return selector
    }

    fun findElement(
        text: String? = null,
        textContains: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDescription: String? = null
    ): ElementResult {
        return try {
            val selector = buildSelector(text, textContains, resourceId, className, contentDescription)
                ?: return ElementResult(found = false, error = "No selector provided")

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

    fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        steps: Int
    ): OperationResult {
        return try {
            val device = getUiDevice()
            Log.d(TAG, "Performing swipe from ($startX, $startY) to ($endX, $endY) in $steps steps")
            val success = device.swipe(startX, startY, endX, endY, steps)
            Log.d(TAG, "Swipe completed with success=$success")
            OperationResult(success = success)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing swipe", e)
            OperationResult(success = false, error = e.message)
        }
    }

    /**
     * Performs a swipe in the specified direction.
     *
     * This is a convenience method that calculates coordinates based on direction,
     * making it easier for agents to perform common gestures without knowing screen dimensions.
     *
     * @param direction The direction to swipe (UP, DOWN, LEFT, RIGHT)
     * @param distance The distance preset (SHORT, MEDIUM, LONG) - default MEDIUM
     * @param speed The speed preset (SLOW, NORMAL, FAST) - default NORMAL
     * @return [OperationResult] indicating success or failure
     */
    fun swipeByDirection(
        direction: SwipeDirection,
        distance: SwipeDistance = SwipeDistance.MEDIUM,
        speed: SwipeSpeed = SwipeSpeed.NORMAL
    ): OperationResult {
        return try {
            val device = getUiDevice()
            val width = device.displayWidth
            val height = device.displayHeight

            // Calculate center point
            val centerX = width / 2
            val centerY = height / 2

            // Calculate distance as percentage of screen dimension
            val distancePercent = when (distance) {
                SwipeDistance.SHORT -> 0.20
                SwipeDistance.MEDIUM -> 0.40
                SwipeDistance.LONG -> 0.60
            }

            val steps = speed.toSteps()

            // Calculate start and end coordinates based on direction
            val (startX, startY, endX, endY) = when (direction) {
                SwipeDirection.UP -> {
                    // Swipe UP: finger moves from bottom to top (scrolls content down)
                    val offsetY = (height * distancePercent / 2).toInt()
                    listOf(centerX, centerY + offsetY, centerX, centerY - offsetY)
                }
                SwipeDirection.DOWN -> {
                    // Swipe DOWN: finger moves from top to bottom (scrolls content up)
                    val offsetY = (height * distancePercent / 2).toInt()
                    listOf(centerX, centerY - offsetY, centerX, centerY + offsetY)
                }
                SwipeDirection.LEFT -> {
                    // Swipe LEFT: finger moves from right to left
                    val offsetX = (width * distancePercent / 2).toInt()
                    listOf(centerX + offsetX, centerY, centerX - offsetX, centerY)
                }
                SwipeDirection.RIGHT -> {
                    // Swipe RIGHT: finger moves from left to right
                    val offsetX = (width * distancePercent / 2).toInt()
                    listOf(centerX - offsetX, centerY, centerX + offsetX, centerY)
                }
            }

            Log.d(TAG, "Performing swipe $direction (distance=$distance, speed=$speed) from ($startX, $startY) to ($endX, $endY) in $steps steps")
            val success = device.swipe(startX, startY, endX, endY, steps)
            Log.d(TAG, "Swipe $direction completed with success=$success")
            OperationResult(success = success)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing swipe by direction", e)
            OperationResult(success = false, error = e.message)
        }
    }

    /**
     * Performs a swipe on a specific element in the specified direction.
     *
     * Finds the element using the provided selectors, then swipes within its bounds.
     * Useful for carousels, sliders, or any scrollable element that isn't full-screen.
     *
     * @param direction The direction to swipe (UP, DOWN, LEFT, RIGHT)
     * @param text Exact text match for finding the element
     * @param textContains Partial text match
     * @param resourceId Resource ID (e.g., "com.example:id/carousel")
     * @param className Class name (e.g., "androidx.recyclerview.widget.RecyclerView")
     * @param contentDescription Accessibility content description
     * @param speed The speed preset (SLOW, NORMAL, FAST) - default NORMAL
     * @return [OperationResult] indicating success or failure
     */
    fun swipeOnElement(
        direction: SwipeDirection,
        text: String? = null,
        textContains: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDescription: String? = null,
        speed: SwipeSpeed = SwipeSpeed.NORMAL
    ): OperationResult {
        return try {
            val device = getUiDevice()

            // Build selector by combining all provided criteria
            val selector = buildSelector(text, textContains, resourceId, className, contentDescription)
                ?: return OperationResult(success = false, error = "No selector provided")

            // Find the element
            val element = device.findObject(selector)
                ?: return OperationResult(success = false, error = "Element not found")

            val bounds = element.visibleBounds
                ?: return OperationResult(success = false, error = "Element has no visible bounds")

            // Calculate center and swipe distance within element bounds
            val centerX = (bounds.left + bounds.right) / 2
            val centerY = (bounds.top + bounds.bottom) / 2

            // Use 70% of element dimension for swipe distance
            val horizontalOffset = ((bounds.right - bounds.left) * 0.35).toInt()
            val verticalOffset = ((bounds.bottom - bounds.top) * 0.35).toInt()

            val steps = speed.toSteps()

            // Calculate start and end coordinates based on direction
            val (startX, startY, endX, endY) = when (direction) {
                SwipeDirection.UP -> {
                    listOf(centerX, centerY + verticalOffset, centerX, centerY - verticalOffset)
                }
                SwipeDirection.DOWN -> {
                    listOf(centerX, centerY - verticalOffset, centerX, centerY + verticalOffset)
                }
                SwipeDirection.LEFT -> {
                    listOf(centerX + horizontalOffset, centerY, centerX - horizontalOffset, centerY)
                }
                SwipeDirection.RIGHT -> {
                    listOf(centerX - horizontalOffset, centerY, centerX + horizontalOffset, centerY)
                }
            }

            Log.d(TAG, "Performing swipe $direction on element within bounds $bounds from ($startX, $startY) to ($endX, $endY)")
            val success = device.swipe(startX, startY, endX, endY, steps)
            Log.d(TAG, "Swipe on element completed with success=$success")
            OperationResult(success = success)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing swipe on element", e)
            OperationResult(success = false, error = e.message)
        }
    }

    /**
     * Gets a filtered list of interactive elements on the current screen.
     *
     * Uses heuristics to identify elements that are likely meaningful to interact with,
     * even if accessibility properties are not properly set by developers.
     *
     * Includes elements that:
     * - Have clickable, checkable, scrollable, or long-clickable = true
     * - Are instances of known interactive classes (Button, EditText, etc.)
     * - Have non-empty text or content-description
     * - Have a resource-id (developer named it)
     *
     * Excludes:
     * - Pure layout containers without meaningful content
     * - Elements with empty/invalid bounds
     * - Disabled elements (optional, controlled by includeDisabled param)
     *
     * @param includeDisabled Whether to include disabled elements (default: false)
     * @return [InteractiveElementsResult] containing the filtered list of elements
     */
    fun getInteractiveElements(includeDisabled: Boolean = false): InteractiveElementsResult {
        return try {
            val device = getUiDevice()
            val uiAutomation = getUiAutomation()
            val displayRect = getDisplayRect()

            device.waitForIdle()

            val elements = mutableListOf<InteractiveElement>()

            // Get all window roots using reflection
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

            Log.d(TAG, "Found ${roots.size} window roots for interactive elements")

            roots.forEach { root ->
                collectInteractiveElements(root, elements, displayRect, includeDisabled)
                root.recycle()
            }

            Log.d(TAG, "Found ${elements.size} interactive elements")

            InteractiveElementsResult(
                success = true,
                elements = elements,
                count = elements.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting interactive elements", e)
            InteractiveElementsResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Recursively collects interactive elements from the accessibility tree.
     */
    private fun collectInteractiveElements(
        node: AccessibilityNodeInfo,
        elements: MutableList<InteractiveElement>,
        displayRect: Rect,
        includeDisabled: Boolean
    ) {
        // Check if this node should be included and get its bounds in one pass
        val bounds = getIncludedElementBounds(node, displayRect, includeDisabled)
        if (bounds != null) {
            val centerX = (bounds.left + bounds.right) / 2
            val centerY = (bounds.top + bounds.bottom) / 2

            elements.add(
                InteractiveElement(
                    text = node.text?.toString()?.takeIf { it.isNotEmpty() },
                    resourceId = node.viewIdResourceName?.takeIf { it.isNotEmpty() },
                    className = node.className?.toString(),
                    contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotEmpty() },
                    bounds = bounds.toShortString(),
                    centerX = centerX,
                    centerY = centerY,
                    isClickable = node.isClickable,
                    isCheckable = node.isCheckable,
                    isScrollable = node.isScrollable,
                    isLongClickable = node.isLongClickable,
                    isEnabled = node.isEnabled
                )
            )
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isVisibleToUser) {
                    collectInteractiveElements(child, elements, displayRect, includeDisabled)
                }
                child.recycle()
            }
        }
    }

    /**
     * Determines if an element should be included in the interactive elements list.
     * Returns the element's visible bounds if it should be included, or null if it should be excluded.
     *
     * Uses multiple heuristics since developers often forget to set proper accessibility properties.
     */
    private fun getIncludedElementBounds(
        node: AccessibilityNodeInfo,
        displayRect: Rect,
        includeDisabled: Boolean
    ): Rect? {
        // Skip if not visible
        if (!node.isVisibleToUser) return null

        // Skip disabled elements unless explicitly requested
        if (!includeDisabled && !node.isEnabled) return null

        // Check bounds
        val bounds = getVisibleBoundsInScreen(node, displayRect) ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null

        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""

        // Include if explicitly interactive
        if (node.isClickable || node.isCheckable || node.isScrollable || node.isLongClickable) {
            return bounds
        }

        // Include known interactive classes (full or short name match)
        val simpleClassName = className.substringAfterLast('.')
        if (INTERACTIVE_CLASSES.any { className.contains(it) } || simpleClassName in INTERACTIVE_CLASS_SHORT_NAMES) {
            return bounds
        }

        // Check layout container membership once to avoid repeated iterations
        val isLayoutContainer = LAYOUT_CONTAINERS.any { className.contains(it) }

        // Exclude pure layout containers without meaningful content
        if (isLayoutContainer) {
            // Include if it has text, content-desc, or resource-id
            return if (text.isNotEmpty() || contentDesc.isNotEmpty() || resourceId.isNotEmpty()) bounds else null
        }

        // Include TextView/ImageView with text, content-desc, or resource-id
        // These are often used as clickable elements without proper properties
        if (className.contains("TextView") || className.contains("ImageView")) {
            return if (text.isNotEmpty() || contentDesc.isNotEmpty() || resourceId.isNotEmpty()) bounds else null
        }

        // Include anything with meaningful content (text or content-desc)
        // that isn't a layout container
        if (text.isNotEmpty() || contentDesc.isNotEmpty()) {
            return bounds
        }

        // Include if it has a resource-id (developer named it, probably important)
        // but only if it's not a layout container
        if (resourceId.isNotEmpty() && !isLayoutContainer) {
            return bounds
        }

        return null
    }
}
