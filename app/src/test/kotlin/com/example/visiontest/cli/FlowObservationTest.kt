package com.example.visiontest.cli

import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.cli.commands.FlowCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
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
                observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, fixture.components, artifacts = artifacts),
                onFailure = listOf("screenshot", "hierarchy", "interactive-elements", "hierarchy")))
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
    fun `collection errors preserve mapped failure and continue evidence without artifacts`() = runBlocking {
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
                }), observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, fixture.components, artifacts = artifacts),
                onFailure = listOf("hierarchy", "interactive-elements", "screenshot")))
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
                observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, fixture.components),
                onFailure = listOf("screenshot", "hierarchy", "interactive-elements"), evidenceTimeoutMs = 40))
            assertEquals(listOf("failed", "skipped", "skipped"), report.evidence.map { it.status })
            assertEquals(1, report.exitCode)
            assertEquals("blocked", report.error?.message)
        }
    }

    @Test
    fun `flows collect nothing by default`() = runBlocking {
        val components = lazy<ComponentHolder> { error("unexpected device access") }
        val report = FlowExecutor().execute(Platform.Android, listOf(failedStep()),
            observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(Platform.Android, components)))
        assertTrue(report.evidence.isEmpty())
        assertFalse(components.isInitialized())
    }


    @Test
    fun `final observation errors keep completed steps and preserve mapped exception exits`() = runBlocking {
        val failures = listOf(IllegalStateException("generic") to 1, IllegalArgumentException("invalid") to 2,
            ServerNotRunningException("unavailable") to 3, NoDeviceAvailableException("missing") to 4,
            CliExit(ExitCode.PlatformNotSupported, "unsupported") to 5)
        for (platform in Platform.entries) for ((error, exit) in failures) {
            val components = lazy<ComponentHolder> { throw error }
            val report = FlowExecutor().execute(platform, listOf(successfulStep()),
                observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, components),
                observe = listOf("hierarchy", "screenshot"), onFailure = listOf("interactive-elements")))
            assertFalse(report.success)
            assertEquals(exit, report.exitCode)
            assertNull(report.failedStep)
            assertEquals("succeeded", report.steps.single().status)
            assertEquals(listOf("failed", "skipped"), report.observations.map { it.status })
            assertEquals("failed", report.evidence.single().status)
            assertEquals(error.message, report.error?.message)
        }
    }

    @Test
    fun `final observations use remaining overall budget and evidence receives a new budget`() = runBlocking {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method ->
                if (method == "ui.getInteractiveElements") MockResponse()
                    .setBody("""{"result":{"success":true,"count":0,"elements":[]}}""")
                    .setBodyDelay(250, TimeUnit.MILLISECONDS)
                else MockResponse().setBody("""{"result":{"success":false,"error":"evidence unavailable"}}""")
            }
            val step = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                delay(40)
                """{"result":{"success":true}}"""
            }
            val report = FlowExecutor().execute(platform, listOf(step), timeoutMs = 100,
                observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, fixture.components),
                observe = listOf("interactive-elements", "hierarchy"), onFailure = listOf("screenshot"),
                evidenceTimeoutMs = 1000))
            assertFalse(report.success)
            assertEquals("succeeded", report.steps.single().status)
            assertNull(report.failedStep)
            assertEquals(listOf("failed", "skipped"), report.observations.map { it.status })
            assertEquals("evidence unavailable", report.evidence.single().result?.get("error")?.jsonPrimitive
                ?.content?.substringAfter(": "))
            assertTrue(report.error?.type in setOf("TimeoutException", "HttpTimeoutException"))
        }
    }

    @Test
    fun `unrequested final and evidence observations do not initialize components`() = runBlocking {
        val components = lazy<ComponentHolder> { error("unexpected device access") }
        val report = FlowExecutor().execute(Platform.Ios, listOf(successfulStep()),
            observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(Platform.Ios, components), onFailure = listOf("hierarchy")))
        assertTrue(report.success)
        assertEquals("skipped", report.evidence.single().status)
        assertFalse(components.isInitialized())
    }

    @Test
    fun `invalid observation scope and budgets fail before device access`() {
        val invalid = listOf(listOf("--observe", "invalid"), listOf("--on-failure", "invalid"),
            listOf("--evidence-timeout", "0"), listOf("--evidence-timeout", "-1"),
            listOf("--evidence-timeout", "30001"), listOf("--bundle-id", ""),
            listOf("--bundle-id", " "))
        for (platform in Platform.entries) for (args in invalid +
            if (platform == Platform.Android) listOf(listOf("--bundle-id", "com.example")) else emptyList()) {
            val components = lazy<ComponentHolder> { error("unexpected device access") }
            var result: CliResult? = null
            FlowCommand(components, runner = { result = executeCliResultCommand(it) })
                .parse(listOf("-p", platform.value, "--step", "press_home", "--json") + args)
            assertEquals(2, result?.exitCode)
            assertNull(result?.stdout)
            assertFalse(components.isInitialized())
        }
    }

    @Test
    fun `parent cancellation propagates during final observations`() = runBlocking {
        FlowObservationFixture(Platform.Ios).use { fixture ->
            fixture.respond = { MockResponse().setBody("""{"result":{"success":true}}""")
                .setBodyDelay(250, TimeUnit.MILLISECONDS) }
            assertFailsWith<CancellationException> {
                withTimeout(50) {
                    FlowExecutor().execute(Platform.Ios, listOf(successfulStep()),
                        observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(Platform.Ios, fixture.components),
                        observe = listOf("screenshot"), onFailure = listOf("hierarchy")))
                }
            }
            assertFalse("ui.dumpHierarchy" in fixture.calls)
        }
    }

    @Test
    fun `outer iOS bundle scope reaches observations while steps retain their own scope`() {
        FlowObservationFixture(Platform.Ios).use { fixture ->
            fixture.respond = { method -> MockResponse().setBody(when (method) {
                "ui.findElement" -> """{"result":{"found":true}}"""
                "ui.dumpHierarchy" -> """{"result":{"success":true,"hierarchy":"<hierarchy/>"}}"""
                "ui.getInteractiveElements" -> """{"result":{"success":true,"count":0,"elements":[]}}"""
                else -> """{"result":{"success":true}}"""
            }) }
            val report = fixture.run("--bundle-id", "com.observation", "--step",
                "wait_for_element --text Done --bundle-id com.step", "--observe", "interactive-elements",
                "--observe", "hierarchy")
            assertEquals("com.step", fixture.requests[0].getValue("params").jsonObject
                .getValue("bundleId").jsonPrimitive.content)
            fixture.requests.drop(1).forEach {
                assertEquals("com.observation", it.getValue("params").jsonObject
                    .getValue("bundleId").jsonPrimitive.content)
            }
            val path = Path.of(report.getValue("observations").jsonArray[1].jsonObject
                .getValue("result").jsonObject.getValue("path").jsonPrimitive.content)
            assertTrue(path.isAbsolute)
            Files.delete(path)
            Files.delete(path.parent)
        }
    }

    private fun successfulStep() = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
        """{"result":{"success":true}}"""
    }

    private fun failedStep() = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
        """{"result":{"success":false,"error":"blocked"}}"""
    }
}
