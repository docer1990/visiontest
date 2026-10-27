package dev.visiontest.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DispatchEvidenceTest {
    @Test
    fun `read confirmation cannot erase pending host mutation`() {
        val evidence = DispatchEvidence()
        evidence.submittingMutation()
        evidence.submitting("ui.screenshot")
        evidence.confirmed("ui.screenshot")
        assertTrue(evidence.pending)
        evidence.failed()
        assertTrue(evidence.uncertain)
    }

    @Test
    fun `confirmed native mutation is known after later disconnect`() {
        val evidence = DispatchEvidence()
        evidence.submitting("ui.tapByCoordinates")
        evidence.confirmed("ui.tapByCoordinates")
        assertFalse(evidence.pending)
        assertFalse(evidence.uncertain)
    }
    @Test
    fun `host mutation transport failure stays uncertain when caller maps failure`() = kotlinx.coroutines.runBlocking {
        val evidence = DispatchEvidence()
        kotlinx.coroutines.withContext(evidence) {
            runCatching { trackHostMutation { throw java.io.IOException("transport lost") } }
        }
        assertTrue(evidence.uncertain)
        assertTrue(evidence.transportFailed)
    }

    @Test
    fun `unconfirmed host mutation failure is captured before CLI mapping`() {
        val evidence = DispatchEvidence()
        val result = dev.visiontest.cli.executeCliResultCommand(evidence) {
            evidence.submittingMutation()
            throw java.io.IOException("lost SDK transport")
        }
        kotlin.test.assertEquals(1, result.exitCode)
        assertTrue(evidence.uncertain)
    }

}
