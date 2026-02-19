package com.example.visiontest.android

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class AndroidValidationTest {

    // --- isValidPackageName ---

    @Test
    fun `isValidPackageName accepts valid package names`() {
        assertTrue(Android.isValidPackageName("com.example.app"))
        assertTrue(Android.isValidPackageName("com.example.myapp"))
        assertTrue(Android.isValidPackageName("org.test.something"))
        assertTrue(Android.isValidPackageName("com.example.my_app"))
    }

    @Test
    fun `isValidPackageName rejects single segment`() {
        assertFalse(Android.isValidPackageName("myapp"))
    }

    @Test
    fun `isValidPackageName rejects leading dot`() {
        assertFalse(Android.isValidPackageName(".com.example.app"))
    }

    @Test
    fun `isValidPackageName rejects trailing dot`() {
        assertFalse(Android.isValidPackageName("com.example.app."))
    }

    @Test
    fun `isValidPackageName rejects segment starting with digit`() {
        assertFalse(Android.isValidPackageName("com.1example.app"))
    }

    @Test
    fun `isValidPackageName rejects empty string`() {
        assertFalse(Android.isValidPackageName(""))
    }

    @Test
    fun `isValidPackageName rejects segment with single char after dot`() {
        assertFalse(Android.isValidPackageName("com.example.a"))
    }

    // --- validateForwardArgs ---

    @Test
    fun `validateForwardArgs accepts valid tcp port pair`() {
        Android.validateForwardArgs(listOf("tcp:9008", "tcp:9008"))
    }

    @Test
    fun `validateForwardArgs accepts remove with valid port`() {
        Android.validateForwardArgs(listOf("--remove", "tcp:9008"))
    }

    @Test
    fun `validateForwardArgs rejects port below 1024`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateForwardArgs(listOf("tcp:80", "tcp:80"))
        }
    }

    @Test
    fun `validateForwardArgs rejects port above 65535`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateForwardArgs(listOf("tcp:70000", "tcp:70000"))
        }
    }

    @Test
    fun `validateForwardArgs rejects missing tcp prefix`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateForwardArgs(listOf("9008", "9008"))
        }
    }

    @Test
    fun `validateForwardArgs rejects wrong arg count`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateForwardArgs(listOf("tcp:9008"))
        }
    }

    @Test
    fun `validateForwardArgs rejects three args`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateForwardArgs(listOf("tcp:9008", "tcp:9008", "tcp:9008"))
        }
    }

    // --- validateShellArgs ---

    @Test
    fun `validateShellArgs accepts am instrument command`() {
        Android.validateShellArgs(listOf("am", "instrument", "-w", "-e", "port", "9008"))
    }

    @Test
    fun `validateShellArgs rejects first arg not am`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateShellArgs(listOf("pm", "install", "something"))
        }
    }

    @Test
    fun `validateShellArgs rejects second arg not instrument`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateShellArgs(listOf("am", "start", "something"))
        }
    }

    @Test
    fun `validateShellArgs rejects metachar injection in args`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateShellArgs(listOf("am", "instrument", "-w;rm -rf /"))
        }
    }

    @Test
    fun `validateShellArgs rejects too few args`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateShellArgs(listOf("am"))
        }
    }

    // --- validateInstallArgs ---

    @Test
    fun `validateInstallArgs accepts valid apk with temp file`() {
        val tempFile = File.createTempFile("test", ".apk")
        try {
            Android.validateInstallArgs(listOf(tempFile.absolutePath))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `validateInstallArgs accepts flags with valid apk`() {
        val tempFile = File.createTempFile("test", ".apk")
        try {
            Android.validateInstallArgs(listOf("-r", tempFile.absolutePath))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `validateInstallArgs rejects no apk path`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateInstallArgs(listOf("-r"))
        }
    }

    @Test
    fun `validateInstallArgs rejects non-apk extension`() {
        val tempFile = File.createTempFile("test", ".jar")
        try {
            assertFailsWith<IllegalArgumentException> {
                Android.validateInstallArgs(listOf(tempFile.absolutePath))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `validateInstallArgs rejects dangerous chars in path`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateInstallArgs(listOf("/path/to/app;evil.apk"))
        }
    }

    @Test
    fun `validateInstallArgs rejects nonexistent apk file`() {
        assertFailsWith<IllegalArgumentException> {
            Android.validateInstallArgs(listOf("/nonexistent/path/app.apk"))
        }
    }
}
