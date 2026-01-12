package com.example.automationserver

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

/**
 * Instrumentation test that hosts the JSON-RPC automation server.
 *
 * This test runs indefinitely until the instrumentation is killed,
 * providing a long-running server for UI automation via JSON-RPC.
 *
 * Usage:
 * ```
 * adb shell am instrument -w -e port 9008 \
 *   com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner
 * ```
 *
 * To stop the server:
 * ```
 * adb shell am force-stop com.example.automationserver
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AutomationServerTest {

    companion object {
        private const val TAG = "AutomationServerTest"
        private const val ARG_PORT = "port"
        private const val DEFAULT_PORT = 9008
        private const val MAX_UI_DEVICE_RETRIES = 10
        private const val UI_DEVICE_RETRY_DELAY_MS = 1000L
    }

    /**
     * Waits for UiDevice to be available with retries.
     * This handles timing issues on emulators where UiAutomation
     * may not be immediately available.
     */
    private fun waitForUiDevice(instrumentation: android.app.Instrumentation): UiDevice {
        var lastException: Exception? = null

        repeat(MAX_UI_DEVICE_RETRIES) { attempt ->
            try {
                val device = UiDevice.getInstance(instrumentation)
                // Verify it's actually working by calling a method
                device.displayWidth
                Log.i(TAG, "UiDevice obtained successfully on attempt ${attempt + 1}")
                return device
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Failed to get UiDevice (attempt ${attempt + 1}/$MAX_UI_DEVICE_RETRIES): ${e.message}")
                Thread.sleep(UI_DEVICE_RETRY_DELAY_MS)
            }
        }

        throw IllegalStateException(
            "Failed to obtain UiDevice after $MAX_UI_DEVICE_RETRIES attempts",
            lastException
        )
    }

    @Test
    fun runAutomationServer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Get port from arguments or use default
        val arguments = AutomationInstrumentationRunner.arguments
        val port = arguments?.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT

        Log.i(TAG, "==============================================")
        Log.i(TAG, "Starting Automation Server (Instrumentation)")
        Log.i(TAG, "Port: $port")
        Log.i(TAG, "==============================================")

        // Create UiDevice with proper instrumentation context
        // Retry with delay to handle emulator startup timing issues
        val uiDevice = waitForUiDevice(instrumentation)

        // Create and start the JSON-RPC server
        val server = JsonRpcServerInstrumented(
            port = port,
            uiDevice = uiDevice
        )

        runBlocking {
            server.start()
        }

        Log.i(TAG, "JSON-RPC server started successfully")
        Log.i(TAG, "Health check: http://localhost:$port/health")
        Log.i(TAG, "JSON-RPC endpoint: http://localhost:$port/jsonrpc")
        Log.i(TAG, "Waiting indefinitely... (kill process to stop)")

        // Block forever - the test stays alive until instrumentation is killed
        // This keeps the JSON-RPC server running
        val latch = CountDownLatch(1)
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Log.i(TAG, "Server interrupted, shutting down...")
            runBlocking {
                server.stop()
            }
        }
    }
}
