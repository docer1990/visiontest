package com.example.automationserver.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ServerConfigPortTest {

    @Test
    fun `MIN_PORT 1024 is valid`() {
        assertTrue(ServerConfig.isValidPort(1024))
    }

    @Test
    fun `MAX_PORT 65535 is valid`() {
        assertTrue(ServerConfig.isValidPort(65535))
    }

    @Test
    fun `port 1023 is invalid`() {
        assertFalse(ServerConfig.isValidPort(1023))
    }

    @Test
    fun `port 65536 is invalid`() {
        assertFalse(ServerConfig.isValidPort(65536))
    }

    @Test
    fun `port 0 is invalid`() {
        assertFalse(ServerConfig.isValidPort(0))
    }

    @Test
    fun `negative port is invalid`() {
        assertFalse(ServerConfig.isValidPort(-1))
    }

    @Test
    fun `DEFAULT_PORT 9008 is valid`() {
        assertTrue(ServerConfig.isValidPort(ServerConfig.DEFAULT_PORT))
        assertEquals(9008, ServerConfig.DEFAULT_PORT)
    }

    @Test
    fun `mid-range port is valid`() {
        assertTrue(ServerConfig.isValidPort(8080))
    }

    @Test
    fun `MIN_PORT constant is 1024`() {
        assertEquals(1024, ServerConfig.MIN_PORT)
    }

    @Test
    fun `MAX_PORT constant is 65535`() {
        assertEquals(65535, ServerConfig.MAX_PORT)
    }
}
