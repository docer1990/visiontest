package dev.visiontest.daemon

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DaemonBuildIdentityTest {
    @Test fun `generated resource supplies stable sha256 identity`() {
        val first = DaemonBuildIdentity.load()
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first, DaemonBuildIdentity.load())
    }
    @Test fun `missing resource fails clearly`() {
        assertFailsWith<IOException> { DaemonBuildIdentity.load(object : ClassLoader(null) {}) }
    }
}
