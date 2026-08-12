package com.example.visiontest

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end smoke test of the MCP stdio surface against the assembled fat JAR.
 *
 * Spawns `java -jar visiontest.jar` (no args -> MCP server), performs the MCP
 * handshake over stdio (initialize -> notifications/initialized) and asserts that
 * `tools/list` returns exactly the expected tool contract. This is the contract
 * MCP clients (and agents) consume: it catches tool renames/removals, SDK
 * upgrades that break the wire protocol, and packaging regressions, none of
 * which the unit suite can see.
 *
 * Runs only via the `e2eTest` Gradle task (tag "e2e" is excluded from `test`).
 */
@Tag("e2e")
class McpStdioE2ETest {

    companion object {
        private const val RESPONSE_TIMEOUT_SECONDS = 60L

        /**
         * The full MCP tool contract: every tool the server must expose.
         * An agent renaming, adding, or removing a tool must update this list
         * deliberately - that is the point.
         */
        private val EXPECTED_TOOLS = setOf(
            // Android device management
            "available_device_android",
            "list_apps_android",
            "info_app_android",
            "launch_app_android",
            // Android automation
            "install_automation_server",
            "start_automation_server",
            "stop_automation_server",
            "automation_server_status",
            "get_ui_hierarchy",
            "find_element",
            "wait_for_element",
            "wait_until_gone",
            "android_tap_by_coordinates",
            "android_swipe",
            "android_swipe_direction",
            "android_swipe_on_element",
            "android_get_device_info",
            "get_interactive_elements",
            "android_input_text",
            "android_press_back",
            "android_press_home",
            "android_screenshot",
            // iOS device management
            "ios_available_device",
            "ios_list_apps",
            "ios_info_app",
            "ios_launch_app",
            // iOS automation
            "ios_start_automation_server",
            "ios_automation_server_status",
            "ios_get_ui_hierarchy",
            "ios_find_element",
            "ios_wait_for_element",
            "ios_wait_until_gone",
            "ios_tap_by_coordinates",
            "ios_swipe",
            "ios_swipe_direction",
            "ios_get_interactive_elements",
            "ios_get_device_info",
            "ios_input_text",
            "ios_press_home",
            "ios_screenshot",
            "ios_stop_automation_server",
        )
    }

    private var process: Process? = null
    private val stdoutLines = LinkedBlockingQueue<String>()
    private val stderrBuffer = StringBuilder()

    @AfterEach
    fun tearDown() {
        process?.let {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) {
                it.destroyForcibly()
                it.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun jarPath(): Path {
        val prop = System.getProperty("visiontest.jar")
            ?: error("System property 'visiontest.jar' not set; run via the ':app:e2eTest' Gradle task")
        val jar = Path.of(prop)
        assertTrue(jar.exists(), "Shadow JAR not found at $jar; run ':app:shadowJar' first")
        return jar
    }

    /**
     * Starts the MCP server subprocess and wires reader threads. stdout carries
     * the newline-delimited JSON-RPC protocol; stderr carries slf4j logs and is
     * drained so the server never blocks on a full pipe.
     */
    private fun startServer(): Pair<BufferedWriter, Process> {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val proc = ProcessBuilder(javaBin, "-jar", jarPath().toString()).start()
        process = proc

        Thread {
            proc.inputStream.bufferedReader().forEachLine { stdoutLines.put(it) }
        }.apply { isDaemon = true }.start()
        Thread {
            proc.errorStream.bufferedReader().forEachLine { synchronized(stderrBuffer) { stderrBuffer.appendLine(it) } }
        }.apply { isDaemon = true }.start()

        return proc.outputStream.bufferedWriter() to proc
    }

    private fun BufferedWriter.sendLine(json: String) {
        write(json)
        newLine()
        flush()
    }

    /** Reads stdout lines until one parses as a JSON-RPC response with the given id. */
    private fun awaitResponse(id: Int): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESPONSE_TIMEOUT_SECONDS)
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) break
            val line = stdoutLines.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: break
            val parsed = runCatching { JsonParser.parseString(line) }.getOrNull() ?: continue
            if (!parsed.isJsonObject) continue
            val obj = parsed.asJsonObject
            if (obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt == id) return obj
        }
        val stderr = synchronized(stderrBuffer) { stderrBuffer.toString() }
        fail("No JSON-RPC response with id=$id within ${RESPONSE_TIMEOUT_SECONDS}s. Server stderr:\n$stderr")
    }

    @Test
    fun `initialize handshake succeeds and reports server identity`() {
        val (writer, _) = startServer()

        writer.sendLine(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"e2e-smoke","version":"0.0.0"}}}"""
        )
        val response = awaitResponse(1)

        val result = response.getAsJsonObject("result")
            ?: fail("initialize returned no result. Full response: $response")
        val serverInfo = result.getAsJsonObject("serverInfo")
            ?: fail("initialize result has no serverInfo. Full result: $result")
        assertEquals("vision-test", serverInfo.get("name").asString)
        assertTrue(
            result.getAsJsonObject("capabilities").has("tools"),
            "Server should advertise tools capability. Full result: $result",
        )
    }

    @Test
    fun `tools list returns exactly the expected tool contract`() {
        val (writer, _) = startServer()

        writer.sendLine(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"e2e-smoke","version":"0.0.0"}}}"""
        )
        awaitResponse(1)
        writer.sendLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        writer.sendLine("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")

        val response = awaitResponse(2)
        val result = response.getAsJsonObject("result")
            ?: fail("tools/list returned no result. Full response: $response")
        val tools = result.getAsJsonArray("tools")
        val names = tools.map { it.asJsonObject.get("name").asString }.toSet()

        assertEquals(
            EXPECTED_TOOLS,
            names,
            "MCP tool contract changed. Missing: ${EXPECTED_TOOLS - names}. Unexpected: ${names - EXPECTED_TOOLS}",
        )
        assertEquals(EXPECTED_TOOLS.size, tools.size(), "Duplicate tool registrations detected")

        // Every tool must carry a non-empty description and an input schema -
        // agents rely on both to call tools correctly.
        for (tool in tools) {
            val obj = tool.asJsonObject
            val name = obj.get("name").asString
            assertTrue(
                !obj.get("description")?.asString.isNullOrBlank(),
                "Tool '$name' has no description",
            )
            assertTrue(obj.has("inputSchema"), "Tool '$name' has no inputSchema")
        }
    }
}
