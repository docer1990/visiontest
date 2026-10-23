package dev.visiontest.automationserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class NativeTimingIntegrationTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test fun tracedReadOnlyRequestPreservesResponse() = runBlocking {
        withOwnedServer {
            val plain = request(null)
            val traced = request("v1;${"a".repeat(32)};${"b".repeat(32)}")
            assertEquals(plain.body, traced.body)
            assertEquals(plain.status, traced.status)
            assertEquals(plain.contentType, traced.contentType)
            assertNull(plain.header)
            val timing = JsonParser.parseString(String(Base64.UrlSafe.decode(traced.header!!), Charsets.UTF_8)).asJsonObject
            assertEquals("b".repeat(32), timing["requestSpanId"].asString)
            assertTrue(timing["spans"].asJsonArray.any { it.asJsonObject["stage"].asString == "response_encode" })
            assertEquals(plain.body, request("invalid").body)
            assertNull(request("invalid").header)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun concurrentDuplicateRpcIdsHaveIndependentHeaders() = runBlocking {
        withOwnedServer {
            val responses = listOf("b", "c").map { id ->
                async(Dispatchers.IO) { request("v1;${"a".repeat(32)};${id.repeat(32)}", "unknown.method") }
            }.map { it.await() }
            assertEquals(responses[0].body, responses[1].body)
            responses.forEachIndexed { index, response ->
                val timing = JsonParser.parseString(String(Base64.UrlSafe.decode(response.header!!))).asJsonObject
                assertEquals((if (index == 0) "b" else "c").repeat(32), timing["requestSpanId"].asString)
                val spans = timing["spans"].asJsonArray
                val ids = spans.map { it.asJsonObject["spanId"].asString }.toSet()
                assertEquals(spans.size(), ids.size)
                spans.forEach { span ->
                    val parent = span.asJsonObject["parentSpanId"]
                    assertTrue(parent.isJsonNull || parent.asString in ids)
                }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun unsuccessfulOperationRetainsRpcErrorFraming() = runBlocking {
        withOwnedServer {
            val plain = request(null, "unknown.method")
            val traced = request("v1;${"a".repeat(32)};${"b".repeat(32)}", "unknown.method")
            assertEquals(plain, traced.copy(header = null))
            val body = JsonParser.parseString(traced.body).asJsonObject
            assertTrue(body.has("error"))
            assertFalse(body.has("result"))
            val timing = JsonParser.parseString(String(Base64.UrlSafe.decode(traced.header!!))).asJsonObject
            val operation = timing["spans"].asJsonArray.single { it.asJsonObject["stage"].asString == "operation" }
            assertEquals("thrown", operation.asJsonObject["outcome"].asString)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun screenshotReportsCaptureAndEncodingWithoutImageMetadata() = runBlocking {
        withOwnedServer {
            val response = request("v1;${"a".repeat(32)};${"b".repeat(32)}", "ui.screenshot")
            val result = JsonParser.parseString(response.body).asJsonObject["result"].asJsonObject
            assertTrue(result["success"].asBoolean)
            val timingText = String(Base64.UrlSafe.decode(response.header!!))
            assertFalse(timingText.contains("pngBase64"))
            val timing = JsonParser.parseString(timingText).asJsonObject
            val stages = timing["spans"].asJsonArray
                .map { it.asJsonObject["stage"].asString }
            assertTrue(stages.containsAll(listOf("ui_sync", "screenshot_capture", "png_encode", "base64_encode")))
            assertFalse("ui_sync" in timing["missingStages"].asJsonArray.map { it.asString })
        }
    }

    private suspend fun withOwnedServer(block: suspend () -> Unit) = kotlinx.coroutines.coroutineScope {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val server = JsonRpcServerInstrumented(19008, UiDevice.getInstance(instrumentation), instrumentation)
        val serverJob = launch(Dispatchers.IO) { server.start() }
        withTimeout(5000) { while (!server.isRunning) delay(10) }
        try {
            block()
        } finally { server.stop(); serverJob.cancel(); serverJob.join() }
    }

    private data class Response(val body: String, val status: Int, val contentType: String?, val header: String?)
    private fun request(header: String?, method: String = "device.getInfo"): Response {
        val connection = URL("http://127.0.0.1:19008/jsonrpc").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (header != null) connection.setRequestProperty("X-VisionTest-Trace", header)
            connection.outputStream.use { it.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\"}".toByteArray()) }
            return Response(connection.inputStream.bufferedReader().use { it.readText() }, connection.responseCode,
                connection.contentType, connection.getHeaderField("X-VisionTest-Timing"))
        } finally { connection.disconnect() }
    }
}
