package com.example.visiontest.performance

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.android.AutomationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTraceTest {
    @Test
    fun `public clients retain JVM no argument constructors`() {
        AutomationClient::class.java.getConstructor().newInstance()
        IOSAutomationClient::class.java.getConstructor().newInstance()
    }

    @Test
    fun `tracing preserves raw response and request count`() = runTest {
        MockWebServer().use { server ->
            val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"success\":false}}"
            server.enqueue(MockResponse().setBody(body))
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(host = server.hostName, port = server.port, trace = trace)
            val result = trace.invocation("android_press_home", "android") {
                client.sendRequest("device.pressHome")
            }
            assertEquals(body, result)
            assertEquals(1, server.requestCount)
            assertEquals(1, events.count { it.stage == TraceStage.HTTP_EXCHANGE })
        }
    }
    @Test
    fun `transport stages count UTF8 bodies without exposing data`() = runTest {
        MockWebServer().use { server ->
            val body = """ {"jsonrpc":"2.0","id":23,"result":{"success":false,"error":"秘密-response"}} """
            server.enqueue(MockResponse().setBody(body))
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(server.hostName, server.port, trace)
            trace.invocation("input_text", "android") {
                assertEquals(body, client.sendRequest("ui.inputText", mapOf("text" to "秘密-argument"), 23))
            }
            val request = server.takeRequest()
            val exchange = events.single { it.stage == TraceStage.HTTP_EXCHANGE }
            assertEquals(request.body.size, exchange.metrics[TraceMetric.REQUEST_BYTES])
            assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), exchange.metrics[TraceMetric.RESPONSE_BYTES])
            assertEquals(1, events.count { it.stage == TraceStage.REQUEST_PREPARE })
            val processing = events.single { it.stage == TraceStage.RESPONSE_PROCESS }
            assertEquals(OperationOutcome.FAILURE, processing.operationOutcome)
            assertTrue(request.body.readUtf8().contains("\"id\":23"))
            assertFalse(events.joinToString { it.toJson().toString() }.contains("秘密"))
        }
    }

    @Test
    fun `health failure is a returned failed operation`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(server.hostName, server.port, trace)
            trace.invocation("automation_server_status", "android") { assertFalse(client.isServerRunning()) }
            val health = events.single { it.stage == TraceStage.HEALTH }
            assertEquals(TraceOutcome.RETURNED, health.outcome)
            assertEquals(OperationOutcome.FAILURE, health.operationOutcome)
            assertEquals(1, server.requestCount)
        }
    }
    @Test
    fun `malformed and nonboolean results remain raw with unknown classification`() = runTest {
        MockWebServer().use { server ->
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(server.hostName, server.port, trace)
            val bodies = listOf(
                "not json", "[]", "null", """{"result":{"success":"false"}}""",
                "{result:{success:true}}",
                "{'result':{'success':true}}",
                """{/* comment */"result":{"success":true}}""",
                """{"result":{"success":true}} trailing""",
            )
            bodies.forEach { body ->
                server.enqueue(MockResponse().setBody(body))
                trace.invocation("find_element", "android") { assertEquals(body, client.findElement(text = "secret")) }
            }
            assertEquals(bodies.size, server.requestCount)
            assertTrue(events.filter { it.stage == TraceStage.RESPONSE_PROCESS }
                .all { it.operationOutcome == OperationOutcome.UNKNOWN })
        }
    }

    @Test
    fun `HTTP failure retains original error and body byte counts`() = runTest {
        MockWebServer().use { server ->
            val body = "秘密-error"
            server.enqueue(MockResponse().setResponseCode(503).setBody(body))
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(server.hostName, server.port, trace)
            trace.invocation("press_home", "android") {
                val error = assertFailsWith<CommandExecutionException> { client.sendRequest("device.pressHome") }
                assertEquals(503, error.exitCode)
                assertEquals("HTTP error: 503 - $body", error.message)
            }
            val exchange = events.single { it.stage == TraceStage.HTTP_EXCHANGE }
            assertEquals(TraceOutcome.THROWN, exchange.outcome)
            assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), exchange.metrics[TraceMetric.RESPONSE_BYTES])
            assertFalse(events.joinToString { it.toJson().toString() }.contains(body))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `HTTP read timeout retains configured timeout and records timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(200, TimeUnit.MILLISECONDS))
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(server.hostName, server.port, trace)
            trace.invocation("press_home", "android") {
                assertFailsWith<SocketTimeoutException> {
                    client.sendRequest("device.pressHome", readTimeoutMs = 20)
                }
            }
            val exchange = events.single { it.stage == TraceStage.HTTP_EXCHANGE }
            assertEquals(TraceOutcome.TIMEOUT, exchange.outcome)
            assertEquals(TraceErrorCategory.TRANSPORT, exchange.errorCategory)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `cancellation while HTTP is active remains cancellation`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS))
            val events = CopyOnWriteArrayList<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(server.hostName, server.port, trace)
            val call = async {
                trace.invocation("press_home", "android") { client.sendRequest("device.pressHome") }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest() }
            call.cancel()
            assertFailsWith<CancellationException> { call.await() }
            assertEquals(TraceOutcome.CANCELLED, events.single { it.stage == TraceStage.INVOCATION }.outcome)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `concurrent invocations stay distinct despite equal RPC IDs`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"id":1,"result":{"success":true}}""")) }
            val events = CopyOnWriteArrayList<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val client = AutomationClient(server.hostName, server.port, trace)
            List(2) { async { trace.invocation("press_home", "android") { client.sendRequest("device.pressHome") } } }
                .awaitAll()
            assertEquals(2, server.requestCount)
            repeat(2) { assertTrue(server.takeRequest().body.readUtf8().contains("\"id\":1")) }
            val invocations = events.filter { it.stage == TraceStage.INVOCATION }
            assertEquals(2, invocations.map { it.invocationId }.distinct().size)
            invocations.forEach { invocation ->
                val spans = events.filter {
                    it.invocationId == invocation.invocationId && it.stage != TraceStage.INVOCATION
                }
                assertEquals(3, spans.size)
                assertTrue(spans.all { it.parentSpanId == invocation.spanId })
                val processing = spans.single { it.stage == TraceStage.RESPONSE_PROCESS }
                assertEquals(OperationOutcome.SUCCESS, processing.operationOutcome)
            }
        }
    }

}
