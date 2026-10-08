package com.example.visiontest.performance

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlin.test.assertSame
import kotlin.test.assertFalse
import com.example.visiontest.tools.ToolScope
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceActivationTest {
    @TempDir lateinit var directory: Path

    @Test fun `CLI ignores MCP environment and normalizes explicit paths`() {
        assertNull(resolveTracePath("cli", null, "private.jsonl"))
        assertNull(resolveTracePath("cli", null, null))
        assertEquals(Path.of("trace.jsonl").toAbsolutePath(), resolveTracePath("cli", "sub/../trace.jsonl", "ignored"))
        assertFailsWith<IllegalArgumentException> { resolveTracePath("cli", "  ", null) }
    }

    @Test fun `MCP blank environment disables and nonblank resolves`() {
        assertNull(resolveTracePath("mcp", null, " "))
        assertNull(resolveTracePath("mcp", null, null))
        assertEquals(Path.of("trace.jsonl").toAbsolutePath(), resolveTracePath("mcp", null, "trace.jsonl"))
    }

    @Test fun `runtime closes session after correlated invocation`() = runBlocking {
        val path = directory.resolve("trace.jsonl")
        val runtime = TraceRuntime("cli", System.nanoTime())
        runtime.configure(path)
        runtime.recorder.invocation("init", null) { runtime.recorder.span(TraceStage.OPERATION) { "ok" } }
        assertTrue(runtime.finish().complete)
        val rows = Files.readAllLines(path).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("session", rows.first()["type"]!!.jsonPrimitive.content)
        assertEquals("session.end", rows.last()["type"]!!.jsonPrimitive.content)
        assertEquals("true", rows.last()["complete"]!!.jsonPrimitive.content)
        assertEquals("1", rows.last()["invocationsStarted"]!!.jsonPrimitive.content)
        assertEquals("1", rows.last()["invocationsCompleted"]!!.jsonPrimitive.content)
        assertEquals(1, rows.count { it["stage"]?.jsonPrimitive?.content == "invocation" })
    }
    @Test fun `MCP timeout retains one invocation across IO context`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val recorder = TraceRecorder(emit = { events.add(it); Unit })
        val server = mockk<Server>(relaxed = true)
        val captured = slot<suspend (CallToolRequest) -> CallToolResult>()
        ToolScope(server, LoggerFactory.getLogger("test"), 50L, recorder).tool("ios_input_text", "test") {
            withContext(Dispatchers.IO) { recorder.span(TraceStage.OPERATION) { awaitCancellation() } }
        }
        verify { server.addTool("ios_input_text", "test", any(), capture(captured)) }
        captured.captured(CallToolRequest(name = "ios_input_text"))
        val invocation = events.single { it.stage == TraceStage.INVOCATION }
        val child = events.single { it.stage == TraceStage.OPERATION }
        assertEquals(TraceOutcome.TIMEOUT, invocation.outcome)
        assertEquals("ios", invocation.platform)
        assertEquals(invocation.invocationId, child.invocationId)
        assertEquals(invocation.spanId, child.parentSpanId)
    }

    @Test fun `shutdown with running invocation remains incomplete and diagnoses once`() = runBlocking {
        val path = directory.resolve("running.jsonl")
        val warnings = mutableListOf<String>()
        val runtime = TraceRuntime("mcp", System.nanoTime(), warnings::add)
        runtime.configure(path)
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val call = launch {
            runtime.recorder.invocation("ios_input_text", "ios") {
                entered.complete(Unit)
                released.await()
            }
        }
        entered.await()
        assertFalse(runtime.finish().complete)
        assertFalse(runtime.finish().complete)
        val footer = Json.parseToJsonElement(Files.readAllLines(path).last()).jsonObject
        assertEquals("false", footer["complete"]!!.jsonPrimitive.content)
        assertEquals("1", footer["invocationsStarted"]!!.jsonPrimitive.content)
        assertEquals("0", footer["invocationsCompleted"]!!.jsonPrimitive.content)
        assertEquals(listOf("VisionTest performance trace is unavailable or incomplete."), warnings)
        released.complete(Unit)
        call.join()
    }

    @Test fun `CLI preparation ends before component initialization and operation`() = runBlocking {
        val path = directory.resolve("boundaries.jsonl")
        val runtime = TraceRuntime("cli", System.nanoTime())
        runtime.configure(path)
        runtime.cliInvocation("get_device_info") {
            runtime.initializeComponents { "components" }
            runtime.recorder.span(TraceStage.OPERATION) { "result" }
        }
        runtime.finish()
        val spans = Files.readAllLines(path).map { Json.parseToJsonElement(it).jsonObject }
        val preparation = spans.single { it["stage"]?.jsonPrimitive?.content == "cli.prepare" }
        val component = spans.single { it["stage"]?.jsonPrimitive?.content == "component.init" }
        val operation = spans.single { it["stage"]?.jsonPrimitive?.content == "operation" }
        assertTrue(preparation["durationNs"]!!.jsonPrimitive.content.toLong() <=
            component["startOffsetNs"]!!.jsonPrimitive.content.toLong())
        assertTrue(component["startOffsetNs"]!!.jsonPrimitive.content.toLong() <=
            operation["startOffsetNs"]!!.jsonPrimitive.content.toLong())
        assertEquals(component["invocationId"], preparation["invocationId"])
    }

    @Test fun `cancelled CLI dispatch closes preparation without replacing cancellation`() = runBlocking {
        val path = directory.resolve("cancelled.jsonl")
        val runtime = TraceRuntime("cli", System.nanoTime())
        runtime.configure(path)
        val original = CancellationException("private")
        val thrown = assertFailsWith<CancellationException> {
            runtime.cliInvocation("init") {
                currentCoroutineContext()[Job]!!.cancel(original)
                throw original
            }
        }
        assertSame(original, thrown)
        runtime.finish()
        val rows = Files.readAllLines(path).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(1, rows.count { it["stage"]?.jsonPrimitive?.content == "cli.prepare" })
        assertFalse(Files.readString(path).contains("private"))
    }

    @Test fun `finish freezes counts before drain while active invocation completes`() = runBlocking {
        val output = StringWriter()
        val flushing = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val writer = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) = output.write(buffer, offset, count)
            override fun close() = Unit
            override fun flush() {
                flushing.countDown()
                check(releaseFlush.await(5, TimeUnit.SECONDS))
            }
        }
        val warnings = mutableListOf<String>()
        val runtime = TraceRuntime("mcp", System.nanoTime(), warnings::add) { _, report ->
            JsonlTraceSink(writer, report)
        }
        runtime.configure(directory.resolve("race.jsonl"))
        val entered = CompletableDeferred<Unit>()
        val releaseCall = CompletableDeferred<Unit>()
        val call = async(Dispatchers.Default) {
            runtime.recorder.invocation("ios_input_text", "ios") {
                entered.complete(Unit)
                releaseCall.await()
                "original result"
            }
        }
        entered.await()
        val finished = CompletableFuture<TraceCloseResult>()
        val finisher = Thread { finished.complete(runtime.finish()) }.apply { start() }
        try {
            assertTrue(flushing.await(5, TimeUnit.SECONDS))
            // Finish has stopped sink acceptance; the invocation returns during its prefooter flush.
            releaseCall.complete(Unit)
            assertEquals("original result", call.await())
            // Operations admitted after the tracing cutoff still run normally.
            assertEquals("late result", runtime.recorder.invocation("ios_input_text", "ios") { "late result" })
            releaseFlush.countDown()
            val result = finished.get(5, TimeUnit.SECONDS)
            assertFalse(result.complete)
            assertEquals(result, runtime.finish())
            val rows = output.toString().lineSequence().filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }.toList()
            val footer = rows.last()
            assertEquals("false", footer["complete"]!!.jsonPrimitive.content)
            assertEquals("1", footer["invocationsStarted"]!!.jsonPrimitive.content)
            assertEquals("0", footer["invocationsCompleted"]!!.jsonPrimitive.content)
            assertEquals(0, rows.count { it["stage"]?.jsonPrimitive?.content == "invocation" })
            assertEquals(listOf("VisionTest performance trace is unavailable or incomplete."), warnings)
        } finally {
            releaseCall.complete(Unit)
            releaseFlush.countDown()
            finisher.join(5_000)
        }
    }

}
