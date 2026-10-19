package com.example.visiontest.cli

import com.example.visiontest.ServerNotRunningException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowObservationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `evidence saves screenshots and XML and validates interactive elements on both platforms`() = runBlocking {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method -> MockResponse().setBody(when (method) {
                "ui.screenshot" -> """{"result":{"success":true,"pngBase64":"AQID"}}"""
                "ui.dumpHierarchy" -> """{"result":{"success":true,"hierarchy":"<hierarchy/>"}}"""
                else -> """{"result":{"success":true,"count":0,"elements":[]}}"""
            }) }
            val artifacts = directory.resolve(platform.value)
            val report = FlowExecutor().execute(platform, listOf(failedStep()),
                collector = FlowObservationCollector(platform, fixture.components, artifacts = artifacts),
                onFailure = listOf("screenshot", "hierarchy", "interactive-elements", "hierarchy"))
            assertEquals(1, report.exitCode)
            assertEquals(listOf("succeeded", "succeeded", "succeeded", "succeeded"), report.evidence.map { it.status })
            val paths = report.evidence.mapNotNull { it.result?.get("path")?.jsonPrimitive?.content?.let(Path::of) }
            assertEquals(3, paths.toSet().size)
            assertTrue(paths.all { it.isAbsolute && Files.exists(it) })
            assertEquals("<hierarchy/>", Files.readString(paths[1]))
            assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(paths[0]).toList())
            assertFalse(report.toJson().toString().contains("pngBase64"))
            assertFalse(report.toJson().toString().contains("<hierarchy"))
            assertTrue(paths.all { report.text().contains(it.toString()) })
            assertEquals(listOf("ui.screenshot", "ui.dumpHierarchy", "ui.getInteractiveElements", "ui.dumpHierarchy"),
                fixture.calls)
        }
    }

    @Test
    fun `collection errors preserve mapped action failure and continue evidence without creating artifacts`() = runBlocking {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method -> MockResponse().setBody(when (method) {
                "ui.dumpHierarchy" -> """{"result":{"success":true,"hierarchy":42}}"""
                "ui.getInteractiveElements" -> """{"result":{"success":true,"count":"0","elements":[]}}"""
                else -> """{"result":{"success":false,"error":"screen unavailable"}}"""
            }) }
            val artifacts = directory.resolve(platform.value)
            val report = FlowExecutor().execute(platform,
                listOf(FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                    throw ServerNotRunningException("original unavailable")
                }), collector = FlowObservationCollector(platform, fixture.components, artifacts = artifacts),
                onFailure = listOf("hierarchy", "interactive-elements", "screenshot"))
            assertEquals(3, report.exitCode)
            assertEquals("original unavailable", report.error?.message)
            assertEquals(listOf("failed", "failed", "failed"), report.evidence.map { it.status })
            assertFalse(Files.exists(artifacts))
        }
    }

    @Test
    fun `evidence shares one total budget and skips pending requests when it expires`() = runBlocking {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { MockResponse().setBody("""{"result":{"success":true}}""")
                .setBodyDelay(250, TimeUnit.MILLISECONDS) }
            val report = FlowExecutor().execute(platform, listOf(failedStep()),
                collector = FlowObservationCollector(platform, fixture.components),
                onFailure = listOf("screenshot", "hierarchy", "interactive-elements"), evidenceTimeoutMs = 40)
            assertEquals(listOf("failed", "skipped", "skipped"), report.evidence.map { it.status })
            assertEquals(1, report.exitCode)
            assertEquals("blocked", report.error?.message)
        }
    }

    @Test
    fun `flows collect nothing by default`() = runBlocking {
        val components = lazy<ComponentHolder> { error("unexpected device access") }
        val report = FlowExecutor().execute(Platform.Android, listOf(failedStep()),
            collector = FlowObservationCollector(Platform.Android, components))
        assertTrue(report.evidence.isEmpty())
        assertFalse(components.isInitialized())
    }

    private fun failedStep() = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
        """{"result":{"success":false,"error":"blocked"}}"""
    }
}
