package com.example.automationserver

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Custom instrumentation runner that captures command-line arguments
 * passed via `am instrument -e key value`.
 *
 * Usage:
 * adb shell am instrument -w -e port 9008 \
 *   com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner
 */
class AutomationInstrumentationRunner : AndroidJUnitRunner() {

    companion object {
        /**
         * Arguments passed to the instrumentation via `-e key value`.
         * Accessible by tests to retrieve configuration.
         */
        var arguments: Bundle? = null
            private set
    }

    override fun onCreate(arguments: Bundle?) {
        AutomationInstrumentationRunner.arguments = arguments
        super.onCreate(arguments)
    }
}
