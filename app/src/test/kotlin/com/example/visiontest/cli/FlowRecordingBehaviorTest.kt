package com.example.visiontest.cli

import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.recording.RecordingSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowRecordingBehaviorTest {
    @TempDir lateinit var directory: Path

    @TestFactory
    fun scenarios() = Platform.entries.map { platform ->
        DynamicContainer.dynamicContainer(platform.value, FlowFeatureScenarios.tests(
            mapOf("Save the requested flow recording" to {
                FlowRecordingScenarios(platform, directory.resolve(platform.value)).bindings()
            }, "Reject a flow whose recorder cannot start" to {
                FlowRecordingScenarios(platform, directory.resolve("${platform.value}-startup")).bindings()
            }, "Stop a flow when its recorder exits early" to {
                FlowRecordingScenarios(platform, directory.resolve("${platform.value}-interruption")).bindings()
            }), requireComplete = false, resourcePath = "/features/flow-recording.feature"))
    }

    @Test
    fun `startup steps and finalization share the injected execution clock`() = runTest {
        for (timeout in listOf(500L, 120000L)) {
            val budgets = mutableListOf<Long>()
            val fake = FakeRecordingSession(directory.resolve("budget-$timeout"))
            val session = object : RecordingSession by fake {
                override suspend fun start(deadline: RequestDeadline) {
                    fake.start(deadline)
                    delay(100)
                }
            }
            val executor = FlowExecutor { budget ->
                budgets += budget
                RequestDeadline(budget) { testScheduler.currentTime * 1_000_000 }
            }
            var requiredDuration = 0L
            val report = executor.execute(Platform.Android, listOf(
                FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                    delay(100)
                    """{"result":{"success":true}}"""
                }), timeoutMs = timeout, recordingOptions = FlowRecordingOptions(directory,
                lazy { FlowRecordingFactory { _, _, _, duration -> requiredDuration = duration; session } }))
            assertTrue(report.success)
            assertEquals(listOf(timeout, minOf(10000L, timeout), minOf(35000L, timeout - 100),
                minOf(30000L, timeout - 200)), budgets)
            assertEquals(timeout + 30000, requiredDuration)
        }
    }

    @Test
    fun `failure evidence uses the same injected clock with its own configured budget`() = runTest {
        val budgets = mutableListOf<Long>()
        val executor = FlowExecutor { budget ->
            budgets += budget
            RequestDeadline(budget) { testScheduler.currentTime * 1_000_000 }
        }
        val report = executor.execute(Platform.Android, listOf(
            FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                delay(100)
                """{"result":{"success":false,"error":"blocked"}}"""
            }), timeoutMs = 500, observationOptions = FlowObservationOptions(
            collector = FlowObservationCollector(Platform.Android, lazy { error("Evidence unavailable") }),
            onFailure = listOf("screenshot"), evidenceTimeoutMs = 200))
        assertFalse(report.success)
        assertEquals("blocked", report.error?.message)
        assertEquals("failed", report.evidence.single().status)
        assertEquals(listOf(500L, 500L, 200L), budgets)
    }
}
