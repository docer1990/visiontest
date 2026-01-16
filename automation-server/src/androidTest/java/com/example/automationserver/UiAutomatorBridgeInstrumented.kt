package com.example.automationserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.test.uiautomator.UiDevice
import com.example.automationserver.uiautomator.BaseUiAutomatorBridge

/**
 * UIAutomator bridge for instrumentation context.
 *
 * This is the **recommended** implementation for UIAutomator operations.
 * It uses a pre-configured UiDevice instance that has been properly initialized
 * with a valid Instrumentation from the Android test framework.
 *
 * ## How it works
 *
 * When running via `am instrument`, the Android test framework provides a valid
 * Instrumentation object through [androidx.test.platform.app.InstrumentationRegistry].
 * This Instrumentation has a connection to UiAutomation, which is required for
 * UIAutomator operations like:
 * - Dumping the view hierarchy
 * - Clicking on coordinates
 * - Finding UI elements
 * - Pressing system buttons (back, home)
 *
 * @param device A UiDevice instance obtained from a valid Instrumentation
 * @param instrumentation The Instrumentation instance for UiAutomation access
 * @see BaseUiAutomatorBridge for the implementation of UIAutomator operations
 * @see AutomationServerTest for how this class is used
 */
class UiAutomatorBridgeInstrumented(
    private val device: UiDevice,
    private val instrumentation: Instrumentation
) : BaseUiAutomatorBridge() {

    companion object {
        private const val TAG = "UiAutomatorBridgeInstr"

        /**
         * Flag to retrieve interactive windows from other applications.
         * Required for accessing UI hierarchy of apps other than our own.
         * Available since API 24 (Android N).
         */
        private const val FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 1
    }

    /**
     * Cached UiAutomation instance with FLAG_RETRIEVE_INTERACTIVE_WINDOWS.
     * This flag is required to access windows from other applications (e.g., Flutter apps).
     */
    private val cachedUiAutomation: UiAutomation by lazy {
        Log.i(TAG, "Initializing UiAutomation with FLAG_RETRIEVE_INTERACTIVE_WINDOWS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            instrumentation.getUiAutomation(FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
        } else {
            instrumentation.uiAutomation
        }
    }

    /**
     * Cached display rectangle for bounds calculations.
     */
    private val cachedDisplayRect: Rect by lazy {
        val windowManager = instrumentation.context
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    init {
        // Set compressed layout hierarchy to false at initialization.
        // This is crucial for Flutter apps to expose all accessibility nodes.
        Log.i(TAG, "Setting compressedLayoutHierarchy to false")
        device.setCompressedLayoutHierarchy(false)
    }

    override fun getUiDevice(): UiDevice = device

    override fun getUiAutomation(): UiAutomation = cachedUiAutomation

    override fun getDisplayRect(): Rect = cachedDisplayRect
}
