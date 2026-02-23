package com.example.visiontest.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ErrorHandlerCoroutineTest {

    @Test
    fun `retryOperation delay between attempt 1 and 2 is initialDelayMs`() = runTest {
        var attempts = 0
        val timestamps = mutableListOf<Long>()

        ErrorHandler.retryOperation(maxAttempts = 3, initialDelayMs = 500) {
            timestamps.add(currentTime)
            attempts++
            if (attempts < 2) throw RuntimeException("fail")
            "ok"
        }

        assertEquals(2, attempts)
        assertEquals(0L, timestamps[0])
        assertEquals(500L, timestamps[1])
    }

    @Test
    fun `retryOperation delay between attempt 2 and 3 is double initialDelayMs`() = runTest {
        var attempts = 0
        val timestamps = mutableListOf<Long>()

        ErrorHandler.retryOperation(maxAttempts = 3, initialDelayMs = 500) {
            timestamps.add(currentTime)
            attempts++
            if (attempts < 3) throw RuntimeException("fail")
            "ok"
        }

        assertEquals(3, attempts)
        assertEquals(0L, timestamps[0])
        assertEquals(500L, timestamps[1])
        assertEquals(1500L, timestamps[2])
    }

    @Test
    fun `retryOperation with custom initialDelayMs 100 uses correct backoff`() = runTest {
        var attempts = 0
        val timestamps = mutableListOf<Long>()

        ErrorHandler.retryOperation(maxAttempts = 4, initialDelayMs = 100) {
            timestamps.add(currentTime)
            attempts++
            if (attempts < 4) throw RuntimeException("fail")
            "ok"
        }

        assertEquals(4, attempts)
        assertEquals(0L, timestamps[0])
        assertEquals(100L, timestamps[1])   // 100 * 2^0
        assertEquals(300L, timestamps[2])   // 100 + 100 * 2^1
        assertEquals(700L, timestamps[3])   // 300 + 100 * 2^2
    }

    @Test
    fun `retryOperation exhaustion with virtual time tracks all delays`() = runTest {
        var attempts = 0
        val timestamps = mutableListOf<Long>()

        assertFailsWith<RuntimeException> {
            ErrorHandler.retryOperation(maxAttempts = 3, initialDelayMs = 500) {
                timestamps.add(currentTime)
                attempts++
                throw RuntimeException("fail #$attempts")
            }
        }

        assertEquals(3, attempts)
        assertEquals(0L, timestamps[0])
        assertEquals(500L, timestamps[1])
        assertEquals(1500L, timestamps[2])
    }

    @Test
    fun `retryOperation first attempt has zero delay`() = runTest {
        val timestamps = mutableListOf<Long>()

        ErrorHandler.retryOperation(maxAttempts = 1, initialDelayMs = 500) {
            timestamps.add(currentTime)
            "ok"
        }

        assertEquals(1, timestamps.size)
        assertEquals(0L, timestamps[0])
    }

    @Test
    fun `retryOperation total elapsed time matches sum of delays`() = runTest {
        var attempts = 0

        assertFailsWith<RuntimeException> {
            ErrorHandler.retryOperation(maxAttempts = 4, initialDelayMs = 200) {
                attempts++
                throw RuntimeException("fail")
            }
        }

        // Delays: 200 + 400 + 800 = 1400ms total
        assertEquals(1400L, currentTime)
    }
}
