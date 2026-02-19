package com.example.visiontest.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {

    @Test
    fun `createDefault has expected server name`() {
        val config = AppConfig.createDefault()
        assertEquals("vision-test", config.serverName)
    }

    @Test
    fun `createDefault has expected server version`() {
        val config = AppConfig.createDefault()
        assertEquals("1.0.0", config.serverVersion)
    }

    @Test
    fun `createDefault has expected adb timeout`() {
        val config = AppConfig()
        assertEquals(5000L, config.adbTimeoutMillis)
    }

    @Test
    fun `createDefault has expected tool timeout`() {
        val config = AppConfig()
        assertEquals(10000L, config.toolTimeoutMillis)
    }

    @Test
    fun `createDefault has expected device cache validity period`() {
        val config = AppConfig()
        assertEquals(1000L, config.deviceCacheValidityPeriod)
    }

    @Test
    fun `default logLevel is PRODUCTION`() {
        val config = AppConfig()
        assertEquals(LogLevel.PRODUCTION, config.logLevel)
    }
}
