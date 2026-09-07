package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.google.gson.JsonParser
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import kotlin.test.*

class IOSSwipeToolRegistrarTest {
    private lateinit var http: MockWebServer
    private lateinit var registrar: IOSAutomationToolRegistrar
    private val logger = LoggerFactory.getLogger(javaClass)

    @BeforeTest
    fun setUp() {
        http = MockWebServer().also { it.start() }
        registrar = IOSAutomationToolRegistrar(mockk<DeviceConfig>(),
            IOSAutomationClient(http.hostName, http.port), ToolDiscovery(logger), logger)
    }

    @AfterTest
    fun tearDown() = http.shutdown()

    @Test
    fun `invalid requests are rejected before contacting device`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { registrar.swipeOnElement("up", bundleId = "com.example") }
        assertFailsWith<IllegalArgumentException> { registrar.swipeOnElement("diagonal", text = "Photos") }
        assertFailsWith<IllegalArgumentException> { registrar.swipeOnElement("up", text = "Photos", speed = "warp") }
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `registered tool declares all properties and delegates selectors`() = runBlocking {
        val server = mockk<Server>(relaxed = true)
        registrar.registerTools(ToolScope(server, logger))
        val schema = slot<Tool.Input>()
        val handler = slot<suspend (CallToolRequest) -> CallToolResult>()
        verify { server.addTool(name = "ios_swipe_on_element", description = any(),
            inputSchema = capture(schema), handler = capture(handler)) }
        assertEquals(listOf("direction"), schema.captured.required)
        val properties = schema.captured.properties
        assertEquals(setOf("direction", "speed", "text", "textContains", "resourceId", "className",
            "contentDescription", "bundleId"), properties.keys)
        properties.values.forEach { assertEquals("string", it.jsonObject["type"]!!.jsonPrimitive.content) }
        assertEquals(listOf("up", "down", "left", "right"), properties["direction"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("normal", properties["speed"]!!.jsonObject["default"]!!.jsonPrimitive.content)
        http.enqueue(MockResponse().setBody("OK"))
        http.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        val args = mapOf("direction" to "left", "resourceId" to "gallery", "text" to "Exact", "textContains" to "Part",
            "className" to "ScrollView", "contentDescription" to "Photos", "bundleId" to "com.example")
        val result = handler.captured(CallToolRequest(name = "ios_swipe_on_element",
            arguments = JsonObject(args.mapValues { JsonPrimitive(it.value) })))
        assertNotEquals(true, result.isError)
        assertEquals("/health", http.takeRequest().path)
        val body = JsonParser.parseString(http.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.swipeOnElement", body["method"].asString)
        assertEquals(args + ("speed" to "normal"), body.getAsJsonObject("params").entrySet().associate { it.key to it.value.asString })
    }
}
