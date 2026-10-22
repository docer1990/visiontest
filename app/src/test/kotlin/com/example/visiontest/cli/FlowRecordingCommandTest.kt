package com.example.visiontest.cli

import com.example.visiontest.cli.commands.FlowCommand
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowRecordingCommandTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `recording works with every platform and input source and shares observation artifacts`() {
        val destinations = mutableSetOf<Path>()
        for (platform in Platform.entries) for (source in sources("press_home")) {
            assertRecordedFlow(platform, source, destinations)
        }
    }

    private fun assertRecordedFlow(platform: Platform, source: List<String>, destinations: MutableSet<Path>) {
        FlowObservationFixture(platform).use { fixture ->
            var session: FakeRecordingSession? = null
            var artifacts: Path? = null
            val factory = lazy { FlowRecordingFactory { _, selected, path, duration ->
                assertEquals(platform, selected)
                assertEquals(155000L, duration)
                assertTrue(path.isAbsolute)
                assertEquals("flow-artifacts", path.parent.fileName.toString())
                assertFalse(Files.exists(path))
                assertTrue(destinations.add(path))
                artifacts = path
                FakeRecordingSession(path).also { it.platform = selected; session = it }
            } }
            fixture.respond = { method ->
                val recording = assertNotNull(session)
                assertFalse(recording.terminal.isCompleted)
                recording.events += if (method == "ui.screenshot") "final-observation" else "action"
                MockResponse().setBody(if (method == "ui.screenshot")
                    """{"result":{"success":true,"pngBase64":"AQID"}}"""
                    else """{"result":{"success":true}}""")
            }
            try {
                val result = run(fixture.components, platform, factory, source + listOf("--record", "--json",
                    "--observe", "screenshot", "--on-failure", "hierarchy"), "press_home")
                assertEquals(0, result.exitCode, result.stderr)
                assertNull(result.stderr)
                val json = Json.parseToJsonElement(assertNotNull(result.stdout)).jsonObject
                assertEquals("true", json.getValue("success").toString())
                val video = Path.of(json.getValue("recording").jsonObject.getValue("path").jsonPrimitive.content)
                assertEquals(artifacts, video.parent)
                assertEquals(if (platform == Platform.Ios) "recording.mov" else "recording.mp4",
                    video.fileName.toString())
                assertTrue(Files.exists(video))
                val screenshot = Path.of(json.getValue("observations").jsonArray.single().jsonObject
                    .getValue("result").jsonObject.getValue("path").jsonPrimitive.content)
                assertEquals(artifacts, screenshot.parent)
                assertTrue(Files.exists(screenshot))
                assertEquals(listOf("record-start", "action", "final-observation", "record-stop"),
                    assertNotNull(session).events)
            } finally {
                artifacts?.let(::removeArtifacts)
            }
        }
    }

    @Test
    fun `explicit screenshot output stays outside recording artifacts and text includes video`() {
        for (platform in Platform.entries) assertExplicitScreenshot(platform)
    }

    private fun assertExplicitScreenshot(platform: Platform) {
        FlowObservationFixture(platform).use { fixture ->
            val screenshot = directory.resolve("${platform.value}-explicit.png")
            var artifacts: Path? = null
            val factory = lazy { FlowRecordingFactory { _, selected, path, duration ->
                assertEquals(150000L, duration)
                artifacts = path
                FakeRecordingSession(path).also { it.platform = selected }
            } }
            fixture.respond = { MockResponse().setBody("""{"result":{"success":true,"pngBase64":"AQID"}}""") }
            try {
                val result = run(fixture.components, platform, factory,
                    listOf("--record", "--step", "screenshot --output '$screenshot'"))
                assertEquals(0, result.exitCode, result.stderr)
                assertTrue(Files.exists(screenshot))
                assertTrue(assertNotNull(result.stdout).contains(screenshot.toString()))
                assertTrue(assertNotNull(result.stdout).contains("Recording succeeded"))
                val name = if (platform == Platform.Ios) "recording.mov" else "recording.mp4"
                assertTrue(assertNotNull(result.stdout).contains(assertNotNull(artifacts).resolve(name).toString()))
            } finally {
                artifacts?.let(::removeArtifacts)
            }
        }
    }

    @Test
    fun `invalid later steps and observation options preflight all sources before recording dependencies`() {
        val invalid = listOf("press_home\nunknown" to emptyList(),
            "press_home\npress_home --record" to emptyList(),
            "press_home" to listOf("--observe", "invalid"),
            "press_home" to listOf("--on-failure", "invalid"),
            "press_home" to listOf("--evidence-timeout", "0"),
            "press_home" to listOf("--compact"))
        val before = artifactDirectories()
        for (platform in Platform.entries) for ((input, options) in invalid) for (source in sources(input)) {
            val components = lazy<ComponentHolder> { error("Device components initialized during validation") }
            val factory = lazy<FlowRecordingFactory> { error("Recording dependencies initialized during validation") }
            val result = run(components, platform, factory, source + options + listOf("--record", "--json"), input)
            assertEquals(2, result.exitCode)
            assertNull(result.stdout)
            assertFalse(components.isInitialized())
            assertFalse(factory.isInitialized())
            assertEquals(before, artifactDirectories())
        }
    }

    @Test
    fun `without flag all sources retain report keys without dereferencing recorder`() {
        val before = artifactDirectories()
        for (platform in Platform.entries) for (source in sources("press_home")) {
            FlowObservationFixture(platform).use { fixture ->
                val factory = lazy<FlowRecordingFactory> { error("Unrequested recorder initialized") }
                val result = run(fixture.components, platform, factory, source + "--json", "press_home")
                assertEquals(0, result.exitCode, result.stderr)
                val report = Json.parseToJsonElement(assertNotNull(result.stdout)).jsonObject
                assertEquals(legacyKeys, report.keys)
                assertFalse(factory.isInitialized())
                assertEquals(before, artifactDirectories())
            }
        }
    }

    @Test
    fun `system factory allocates platform sessions without creating artifacts or launching tools`() = runTest {
        for (platform in Platform.entries) {
            val artifacts = directory.resolve(platform.value)
            val session = SystemFlowRecordingFactory().create(this, platform, artifacts, 150000)
            assertFalse(session.terminal.isCompleted)
            assertFalse(Files.exists(artifacts))
        }
    }

    private fun sources(input: String): List<List<String>> {
        val file = Files.createTempFile(directory, "flow-", ".txt")
        Files.writeString(file, input)
        return listOf(input.lines().flatMap { listOf("--step", it) },
            listOf("--file", file.toString()), listOf("--stdin"))
    }

    private fun run(
        components: Lazy<ComponentHolder>,
        platform: Platform,
        factory: Lazy<FlowRecordingFactory>,
        options: List<String>,
        input: String = "",
    ): CliResult {
        var result: CliResult? = null
        FlowCommand(components, runner = { result = executeCliResultCommand(it) }, readStdin = { input },
            recordingFactory = factory).parse(listOf("--platform", platform.value) + options)
        return assertNotNull(result)
    }

    private fun artifactDirectories(): Set<Path> {
        val root = Path.of("flow-artifacts")
        return if (Files.exists(root)) Files.list(root).use { it.toList().toSet() } else emptySet()
    }

    private fun removeArtifacts(path: Path) {
        if (Files.exists(path)) Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        val legacyKeys = setOf("schemaVersion", "platform", "success", "exitCode", "steps", "failedStep",
            "skippedSteps", "observations", "evidence", "error")
    }
}
