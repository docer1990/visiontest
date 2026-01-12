package com.example.automationserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.automationserver.config.ServerConfig
import com.example.automationserver.databinding.ActivityMainBinding

/**
 * Main Activity for the Automation Server app.
 *
 * This app serves as a configuration UI and documentation for the automation server.
 * The actual server runs via Android instrumentation (am instrument), not from this app directly.
 *
 * The app provides:
 * - Port configuration (saved for instrumentation to use)
 * - Instructions for starting the server via ADB
 * - Copy-to-clipboard functionality for the start command
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: ServerConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ServerConfig(this)
        setupUI()
        loadSettings()
        updateCommand()
    }

    private fun setupUI() {
        // Port input validation and auto-save
        binding.portInput.doAfterTextChanged { text ->
            val port = text?.toString()?.toIntOrNull()
            if (port != null) {
                if (!config.isValidPort(port)) {
                    binding.portInput.error = getString(R.string.error_invalid_port)
                } else {
                    binding.portInput.error = null
                    config.port = port
                    updateCommand()
                }
            }
        }

        // Copy command button
        binding.copyButton.setOnClickListener {
            copyCommandToClipboard()
        }
    }

    private fun loadSettings() {
        binding.portInput.setText(config.port.toString())
    }

    private fun updateCommand() {
        val port = config.port
        val command = buildString {
            append("adb shell am instrument -w ")
            append("-e port $port ")
            append("-e class com.example.automationserver.AutomationServerTest#runAutomationServer ")
            append("com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner")
        }
        binding.commandText.text = command
    }

    private fun copyCommandToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Automation Server Command", binding.commandText.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
    }
}
