package com.example.visiontest.cli

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.ios.IOSAutomationClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

internal class FlowLostResponseScenario {
    private val attempts = mutableListOf<Attempt>()
    private var ready = false

    fun bindings(): Map<String, () -> Unit> = mapOf(
        "Given an action is dispatched and its response is lost" to { ready = true },
        "When the caller submits the flow" to {
            check(ready)
            Platform.entries.forEach { attempts += execute(it) }
        },
        "Then its effect is unknown and no action is replayed" to {
            assertEquals(2, attempts.size)
            attempts.forEach { attempt ->
                assertFalse(attempt.report.success)
                assertEquals(1, attempt.report.exitCode)
                assertEquals("uncertain", attempt.report.steps.first().status)
                assertEquals("unknown", attempt.report.steps.first().certainty)
                assertEquals(listOf("step-2"), attempt.report.skippedSteps)
                assertEquals(1, attempt.posts)
                assertEquals(0, attempt.laterCalls)
            }
        },
    )

    private fun execute(platform: Platform): Attempt = MockWebServer().use { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val android = AutomationClient(server.hostName, server.port)
        val ios = IOSAutomationClient(server.hostName, server.port)
        var laterCalls = 0
        val steps = listOf(
            FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                when (platform) {
                    Platform.Android -> android.sendRequest("device.pressHome")
                    Platform.Ios -> ios.sendRequest("device.pressHome")
                }
            },
            FlowPreparedStep("step-2", "press_home", FlowStepKind.ACTION) {
                laterCalls++
                """{"result":{"success":true}}"""
            },
        )
        val report = runBlocking { FlowExecutor().execute(platform, steps, stepTimeoutMs = 500) }
        assertEquals("POST", assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).method)
        Attempt(report, server.requestCount, laterCalls)
    }

    private data class Attempt(val report: FlowReport, val posts: Int, val laterCalls: Int)
}
