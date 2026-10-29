package dev.visiontest.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("e2e")
class FlowRecordingE2ETest {
    @TempDir lateinit var directory: Path

    @TestFactory
    fun `packaged recording accepts every input mode`() = cases().map { (platform, mode) ->
        dynamicTest("$platform $mode saves a video") {
            fixture().use { fixture ->
                val result = fixture.run(platform, mode, null, true)
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                val report = report(result)
                assertTrue(report.getValue("success").jsonPrimitive.boolean)
                assertEquals(listOf("succeeded", "succeeded"), statuses(report))
                val recording = report.getValue("recording").jsonObject
                assertEquals("succeeded", recording.getValue("status").jsonPrimitive.content)
                assertEquals(JsonNull, recording.getValue("phase"))
                assertEquals(JsonNull, recording.getValue("error"))
                assertSavedVideo(fixture, recording, platform)
                assertEquals(listOf("device.pressHome", "device.pressHome"), fixture.calls.toList())
                fixture.assertOwnedCleanup(expectInterrupt = true)
            }
        }
    }

    @TestFactory
    fun `daemon recording retains a healthy graph across a later request`() = listOf(Platform.Ios).map { platform ->
        dynamicTest("$platform daemon recording and followup") {
            fixture().use { fixture ->
                val recording = fixture.run(platform, "step", null, true, standalone = false)
                assertEquals(0, recording.exitCode, recording.stderr + recording.stdout)
                val report = report(recording)
                assertEquals("succeeded", report.getValue("recording").jsonObject
                    .getValue("status").jsonPrimitive.content)
                assertSavedVideo(fixture, report.getValue("recording").jsonObject, platform)
                val daemonPid = fixture.daemonStatus().getValue("pid")
                val followup = fixture.followup(platform)
                assertEquals(0, followup.exitCode, followup.stderr + followup.stdout)
                assertEquals(daemonPid, fixture.daemonStatus().getValue("pid"))
                assertEquals(listOf("device.pressHome", "device.pressHome", "device.pressHome"), fixture.calls.toList())
                fixture.assertOwnedCleanup(expectInterrupt = true)
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun `daemon recording cancellation releases its recorder and preserves partial video`() {
        fixture().use { fixture ->
            val result = fixture.run(Platform.Ios, "step", "premature-zero", true, standalone = false)
            assertEquals(1, result.exitCode, result.stderr + result.stdout)
            val report = report(result)
            assertEquals(listOf("uncertain", "skipped"), statuses(report))
            val recording = report.getValue("recording").jsonObject
            assertEquals("failed", recording.getValue("status").jsonPrimitive.content)
            assertSavedVideo(fixture, recording, Platform.Ios)
            val daemonPid = fixture.daemonStatus().getValue("pid")
            val followup = fixture.diagnosticFollowup(Platform.Ios)
            assertEquals(0, followup.exitCode, followup.stderr + followup.stdout)
            assertTrue(followup.stdout.contains("running and accessible"), followup.stdout)
            assertEquals(daemonPid, fixture.daemonStatus().getValue("pid"))
            assertEquals(listOf("device.pressHome"), fixture.calls.toList())
            fixture.assertOwnedCleanup(expectInterrupt = false)
        }
    }

    @TestFactory
    fun `packaged startup failures emit one complete report`() = cases().map { (platform, mode) ->
        dynamicTest("$platform $mode startup failure") {
            fixture().use { fixture ->
                val result = fixture.run(platform, mode, "startup", true)
                assertEquals(1, result.exitCode)
                val lines = result.stdout.trim().lines()
                assertEquals(1, lines.size)
                val report = Json.parseToJsonElement(lines.single()).jsonObject
                assertEquals(false, report.getValue("success").jsonPrimitive.boolean)
                assertEquals("start", report.getValue("recording").jsonObject.getValue("phase").jsonPrimitive.content)
                assertFalse(report.containsKey("jsonrpc"))
                assertTrue(result.stderr.contains("readiness") ||
                    result.stderr.contains("record", ignoreCase = true), result.stderr)
                assertEquals(listOf("skipped", "skipped"), statuses(report))
                assertTrue(fixture.calls.isEmpty())
                assertTrue(fixture.invocations().isNotEmpty())
                fixture.assertOwnedCleanup(expectInterrupt = false)
            }
        }
    }

    @TestFactory
    fun `invalid later steps prevent packaged recorder discovery`() = cases().map { (platform, mode) ->
        dynamicTest("$platform $mode invalid later step") {
            fixture().use { fixture ->
                val result = fixture.run(platform, mode, "preflight", true)
                assertEquals(2, result.exitCode)
                assertEquals("", result.stdout)
                assertTrue(result.stderr.contains("not supported in flows"))
                assertTrue(fixture.invocations().isEmpty())
                assertEquals(0, fixture.requestCount)
                assertFalse(Files.exists(fixture.directory.resolve("flow-artifacts")))
                fixture.assertOwnedCleanup(expectInterrupt = false)
            }
        }
    }

    @TestFactory
    fun `text output includes the saved recording`() = Platform.entries.map { platform ->
        dynamicTest("$platform text output") {
            fixture().use { fixture ->
                val result = fixture.run(platform, "step", null, false)
                assertEquals(0, result.exitCode, result.stderr)
                assertTrue(result.stdout.contains("Recording succeeded"))
                val extension = if (platform == Platform.Android) "mp4" else "mov"
                val video = Files.walk(fixture.directory).use { paths ->
                    paths.filter { it.fileName.toString() == "recording.$extension" }.findFirst().orElseThrow()
                }
                assertTrue(result.stdout.contains(video.toAbsolutePath().toString()))
                assertFalse(result.stdout.contains("Recording started"))
                fixture.assertOwnedCleanup(expectInterrupt = true)
            }
        }
    }

    @TestFactory
    fun `premature recorder exit cancels execution and preserves a partial video`() =
        Platform.entries.flatMap { platform -> listOf("premature-zero", "premature-nonzero").map { failure ->
            dynamicTest("$platform $failure") {
                fixture().use { fixture ->
                    val result = fixture.run(platform, "step", failure, true)
                    assertEquals(1, result.exitCode, result.stderr + result.stdout)
                    val report = report(result)
                    val recording = report.getValue("recording").jsonObject
                    assertEquals("failed", recording.getValue("status").jsonPrimitive.content)
                    assertEquals("record", recording.getValue("phase").jsonPrimitive.content)
                    assertEquals(listOf("uncertain", "skipped"), statuses(report))
                    assertEquals("unknown", report.getValue("steps").jsonArray.first().jsonObject
                        .getValue("certainty").jsonPrimitive.content)
                    assertEquals(listOf("device.pressHome"), fixture.calls.toList())
                    assertSavedVideo(fixture, recording, platform)
                    fixture.assertOwnedCleanup(expectInterrupt = false)
                }
            }
        } }

    @TestFactory
    fun `text output explains recording failure`() = Platform.entries.map { platform ->
        dynamicTest("$platform text failure") {
            fixture().use { fixture ->
                val result = fixture.run(platform, "step", "invalid-video", false)
                assertEquals(1, result.exitCode, result.stderr)
                assertTrue(result.stdout.contains("Flow failed"))
                assertTrue(result.stdout.contains("Recording failed. "))
                assertTrue(result.stdout.substringAfter("Recording failed. ").isNotBlank())
                assertTrue(result.stderr.contains("record", ignoreCase = true))
                fixture.assertOwnedCleanup(expectInterrupt = true)
            }
        }
    }

    @TestFactory
    fun `invalid final video fails without changing completed steps`() = Platform.entries.map { platform ->
        dynamicTest("$platform invalid final video") {
            fixture().use { fixture ->
                val result = fixture.run(platform, "step", "invalid-video", true)
                assertEquals(1, result.exitCode, result.stderr + result.stdout)
                val report = report(result)
                assertFalse(report.getValue("success").jsonPrimitive.boolean)
                assertEquals(listOf("succeeded", "succeeded"), statuses(report))
                val recording = report.getValue("recording").jsonObject
                assertEquals("failed", recording.getValue("status").jsonPrimitive.content)
                assertEquals("finalize", recording.getValue("phase").jsonPrimitive.content)
                assertEquals(JsonNull, recording.getValue("path"))
                fixture.assertOwnedCleanup(expectInterrupt = true)
            }
        }
    }

    private fun fixture() = RecordingExecutableFixture(Files.createTempDirectory(directory, "invocation-").toRealPath())

    private fun cases() = Platform.entries.flatMap { platform ->
        listOf("step", "file", "stdin").map { platform to it }
    }

    private fun report(result: PackagedRecordingResult): JsonObject {
        assertEquals(1, result.stdout.trim().lines().size)
        assertTrue(result.stdout.endsWith('\n'))
        return Json.parseToJsonElement(result.stdout).jsonObject.also {
            assertFalse(it.containsKey("jsonrpc"))
            assertEquals(result.exitCode.toString(), it.getValue("exitCode").jsonPrimitive.content)
        }
    }

    private fun statuses(report: JsonObject) = report.getValue("steps").jsonArray.map {
        it.jsonObject.getValue("status").jsonPrimitive.content
    }

    private fun assertSavedVideo(fixture: RecordingExecutableFixture, recording: JsonObject, platform: Platform) {
        val path = Path.of(recording.getValue("path").jsonPrimitive.content)
        assertTrue(path.isAbsolute)
        assertTrue(path.startsWith(fixture.directory.resolve("flow-artifacts")))
        assertEquals(if (platform == Platform.Android) "recording.mp4" else "recording.mov", path.fileName.toString())
        assertContentEquals(Files.readAllBytes(fixture.directory.resolve("valid-video")), Files.readAllBytes(path))
    }
}
