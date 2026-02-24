package com.example.visiontest.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppConfigTest {

    @Test
    fun `createDefault has expected server name`() {
        val config = AppConfig.createDefault()
        assertEquals("vision-test", config.serverName)
    }

    @Test
    fun `createDefault has expected server version`() {
        val config = AppConfig.createDefault()
        assertNotNull(config.serverVersion)
        assertTrue(config.serverVersion.isNotBlank())
    }

    @Test
    fun `createDefault has expected adb timeout`() {
        val config = AppConfig.createDefault()
        assertEquals(5000L, config.adbTimeoutMillis)
    }

    @Test
    fun `createDefault has expected tool timeout`() {
        val config = AppConfig.createDefault()
        assertEquals(10000L, config.toolTimeoutMillis)
    }

    @Test
    fun `createDefault has expected device cache validity period`() {
        val config = AppConfig.createDefault()
        assertEquals(1000L, config.deviceCacheValidityPeriod)
    }

    @Test
    fun `default logLevel is PRODUCTION`() {
        val config = AppConfig()
        assertEquals(LogLevel.PRODUCTION, config.logLevel)
    }
}
