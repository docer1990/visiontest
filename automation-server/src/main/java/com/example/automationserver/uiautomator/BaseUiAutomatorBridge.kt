package com.example.automationserver.uiautomator

import android.os.Build
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream

/**
 * Abstract base class for UIAutomator operations.
 *
 * This class provides the common implementation of UIAutomator operations.
 * Subclasses must provide a properly initialized UiDevice instance via [getUiDevice].
 *
 * ## Why this abstraction exists
 *
 * Android's UIAutomator requires a valid [android.app.Instrumentation] context to function.
 * There are different ways to obtain this context:
 *
 * 1. **Instrumentation Tests** (recommended): Running via `am instrument` provides a valid
 *    Instrumentation from [androidx.test.platform.app.InstrumentationRegistry].
 *
 * 2. **Service/Activity context** (limited): Creating an empty `Instrumentation()` doesn't
 *    provide the required UiAutomation connection, so UIAutomator operations will fail.
 *
 * This base class encapsulates all UIAutomator operations, allowing different subclasses
 * to provide the UiDevice in whatever way is appropriate for their context.
 *
 * @see UiAutomatorBridgeInstrumented for the working instrumentation-based implementation
 */
abstract class BaseUiAutomatorBridge {

    companion object {
        private const val TAG = "BaseUiAutomatorBridge"
    }

    /**
     * Returns the UiDevice instance to use for operations.
     * Subclasses must implement this to provide a properly initialized UiDevice.
     */
    protected abstract fun getUiDevice(): UiDevice

    /**
     * Dumps the current UI hierarchy as XML.
     * Returns the complete view hierarchy of the current screen.
     */
    fun dumpHierarchy(): UiHierarchyResult {
        return try {
            val outputStream = ByteArrayOutputStream()
            getUiDevice().dumpWindowHierarchy(outputStream)
            val xmlHierarchy = outputStream.toString("UTF-8")

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
     * Gets device display and system information.
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
     * @return OperationResult indicating success or failure
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
     * @return ElementResult with element info if found, or found=false if not
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
