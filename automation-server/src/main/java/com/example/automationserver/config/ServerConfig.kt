package com.example.automationserver.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration manager for the Automation Server.
 * Handles persistence of server settings using SharedPreferences.
 */
class ServerConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * The port number for the JSON-RPC server.
     * Default: 9008
     */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

    /**
     * Whether to start the server automatically on device boot.
     */
    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()
        }

    /**
     * Validates if a port number is valid.
     * @param port The port number to validate
     * @return true if the port is valid (1024-65535), false otherwise
     */
    fun isValidPort(port: Int): Boolean {
        return port in MIN_PORT..MAX_PORT
    }

    companion object {
        private const val PREFS_NAME = "automation_server_config"
        private const val KEY_PORT = "server_port"
        private const val KEY_START_ON_BOOT = "start_on_boot"

        const val DEFAULT_PORT = 9008
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
