package com.example.automationserver

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
 * ## Usage
 *
 * This class is instantiated by [AutomationServerTest] which obtains the UiDevice
 * from the test runner:
 *
 * ```kotlin
 * val instrumentation = InstrumentationRegistry.getInstrumentation()
 * val uiDevice = UiDevice.getInstance(instrumentation)
 * val bridge = UiAutomatorBridgeInstrumented(uiDevice)
 * ```
 *
 * @param uiDevice A UiDevice instance obtained from a valid Instrumentation
 * @see BaseUiAutomatorBridge for the implementation of UIAutomator operations
 * @see AutomationServerTest for how this class is used
 */
class UiAutomatorBridgeInstrumented(private val device: UiDevice) : BaseUiAutomatorBridge() {

    override fun getUiDevice(): UiDevice = device
}
