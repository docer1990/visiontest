package com.example.visiontest.common

import com.example.visiontest.CommandExecutionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompactInspectionTest {
    internal fun scenarioBindings(): Map<String, () -> Map<String, () -> Unit>> = mapOf(
        "Preserve target information in a compact observation" to ::bindings,
    )

    private fun bindings(): Map<String, () -> Unit> {
        var sources = emptyList<String>()
        var results = emptyList<JsonObject>()
        return mapOf(
            "Given native Android and iOS observations with distinct and duplicate targets" to {
                sources = listOf(false, true).map { observation("""
                    {"resourceId":"save","text":"Save","contentDescription":"Save changes","className":"Button",
                    "value":"Ready","isEnabled":false,"isClickable":true,"isCheckable":false,
                    "isScrollable":false,"isLongClickable":true},
                    {"resourceId":"save","text":"Save","className":"Button","isEnabled":true},
                    {"contentDescription":"Next","text":"Next","value":"Next"},
                    {"text":"Only text"}, {"resourceId":" ","text":"","contentDescription":""}
                """.trimIndent()) }
            },
            "When the caller requests compact elements" to {
                results = sources.mapIndexed { index, source -> parsed(source, ios = index == 1) }
            },
            "Then observed selectors and relevant states remain available without repeated presentation fields" to {
                results.forEachIndexed { index, result ->
                    assertEquals("compact-v1", result["format"]!!.jsonPrimitive.content)
                    assertEquals(5, result["count"]!!.jsonPrimitive.int)
                    assertEquals(5, result["observedCount"]!!.jsonPrimitive.int)
                    val items = result["elements"]!!.jsonArray
                    val first = items[0].jsonObject
                    assertEquals(JsonPrimitive("save"), first["selector"]!!.jsonObject["resourceId"])
                    assertEquals(JsonPrimitive(2), first["selectorMatches"])
                    assertEquals(JsonPrimitive("Save"), first["label"])
                    assertEquals(JsonPrimitive("Save changes"), first["contentDescription"])
                    assertEquals(JsonPrimitive(false), first["enabled"])
                    assertEquals(JsonPrimitive("Button"), first["type"])
                    if (index == 0) {
                        assertEquals(JsonPrimitive(true), first["clickable"])
                        assertEquals(JsonPrimitive(false), first["checkable"])
                        assertEquals(JsonPrimitive(false), first["scrollable"])
                        assertEquals(JsonPrimitive(true), first["longClickable"])
                    } else assertEquals(JsonPrimitive("Ready"), first["value"])
                    assertNull(first["text"])
                    assertEquals(setOf("selector", "label"), items[2].jsonObject.keys)
                    assertEquals(buildJsonObject { put("text", "Only text") }, items[3].jsonObject["selector"])
                    assertEquals(buildJsonObject { put("selector", JsonNull) }, items[4])
                }
            },
        )
    }

    @Test fun `default preserves arbitrary response bytes`() {
        assertEquals(" raw invalid ", CompactInspectionOptions().project(" raw invalid ", false))
    }

    @Test fun `options reject dependent flags and invalid types and regions`() {
        listOf(CompactInspectionOptions(type = "Button"), CompactInspectionOptions(region = "0,0,1,1"),
            CompactInspectionOptions(includeBounds = true), CompactInspectionOptions(true, type = " ")
        ).forEach { assertFailsWith<IllegalArgumentException> { it.validate() } }
        listOf("", "0,0,1", "0,0,1,1,1", "-1,0,1,1", "0,-1,1,1", "0,0,0,1", "0,0,1,-1",
            "2147483647,0,1,1", "0,2147483647,1,1", "0,0,2147483648,1", "0,0,1.0,1"
        ).forEach { region -> assertFailsWith<IllegalArgumentException>(region) {
            CompactInspectionOptions(true, region = region).validate()
        } }
        CompactInspectionOptions(true, region = "2147483646,2147483646,1,1").validate()
    }

    @Test fun `region includes left top and excludes right bottom with duplicate counts before filters`() {
        val source = observation("""
            {"resourceId":"x","className":"Button","centerX":10,"centerY":20,"bounds":"[0,0][20,40]"},
            {"resourceId":"x","className":"Other","centerX":10,"centerY":20},
            {"centerX":20,"centerY":20}, {"centerX":10,"centerY":30}, {"centerX":9,"centerY":20}
        """)
        val result = parsed(source, CompactInspectionOptions(true, "Button", "10,20,10,10", true))
        assertEquals(JsonPrimitive(5), result["observedCount"])
        val regionResult = parsed(source, CompactInspectionOptions(true, region = "10,20,10,10"))
        assertEquals(JsonPrimitive(2), regionResult["count"])
        val exactTypeResult = parsed(source, CompactInspectionOptions(true, type = "button"))
        assertEquals(JsonPrimitive(0), exactTypeResult["count"])
        val item = result["elements"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(2), item["selectorMatches"])
        assertEquals(JsonPrimitive(10), item["centerX"])
        assertEquals(JsonPrimitive(20), item["centerY"])
        assertEquals(JsonPrimitive("[0,0][20,40]"), item["bounds"])
        assertNull(parsed(source)["elements"]!!.jsonArray[0].jsonObject["bounds"])
    }

    @Test fun `ios text selector counts label or value once per element`() {
        val source = observation("""
            {"text":"Hello"}, {"contentDescription":"Hello","value":"Hello"}, {"value":"Hello"}
        """)
        val firstIosElement = parsed(source, ios = true)["elements"]!!.jsonArray[0].jsonObject
        assertEquals(JsonPrimitive(3), firstIosElement["selectorMatches"])
        assertNull(parsed(source)["elements"]!!.jsonArray[0].jsonObject["selectorMatches"])
    }

    @Test fun `all elements validated before filters including missing region centers`() {
        listOf("""{"className":"Other","isEnabled":"false"}""", """{"className":"Other"}""").forEach {
            assertFailsWith<CommandExecutionException> {
                parsed(observation(it), CompactInspectionOptions(true, "Button", "0,0,1,1"))
            }
        }
    }

    @Test fun `malformed responses fail explicitly`() {
        listOf("garbage", "[]", "{}", """{"result":{}}""",
            """{"result":{"success":"true","count":0,"elements":[]}}""",
            """{"result":{"success":true,"count":"0","elements":[]}}""",
            """{"result":{"success":true,"count":0,"elements":{},"error":1}}""",
            """{"error":{"code":"1","message":"bad"}}""",
            observation("null"), observation("""{"text":3}"""), observation("""{"centerX":1.2}"""),
            observation("""{"isScrollable":null}"""), observation("""{"bounds":{}}"""),
            """{"result":{"success":true,"count":0,"elements":[],"error":false}}"""
        ).forEach { assertFailsWith<CommandExecutionException>(it) { parsed(it) } }
        assertFailsWith<CommandExecutionException> { parsed(observation("""{"value":4}"""), ios = true) }
    }

    @Test fun `operation failures require valid count and elements`() {
        listOf(
            """{"success":false,"error":"Not ready"}""",
            """{"success":false,"elements":[],"error":"Not ready"}""",
            """{"success":false,"count":0,"error":"Not ready"}""",
            """{"success":false,"count":"0","elements":[],"error":"Not ready"}""",
            """{"success":false,"count":0,"elements":{},"error":"Not ready"}""",
        ).forEach { failure ->
            assertFailsWith<CommandExecutionException>(failure) { parsed("""{"result":$failure}""") }
        }
    }

    @Test fun `operation and rpc failures remain unwrapped`() {
        val failure = """{"success":false,"count":0,"elements":[],"error":"Not ready"}"""
        assertEquals(Json.parseToJsonElement(failure), parsed("""{"result":$failure}"""))
        assertEquals(Json.parseToJsonElement("""{"error":{"code":-1,"message":"Not ready"}}"""),
            parsed("""{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"Not ready"}}"""))
    }

    private fun observation(elements: String): String {
        val array = Json.parseToJsonElement("[$elements]").jsonArray
        return """{"result":{"success":true,"count":${array.size},"elements":$array}}"""
    }

    private fun parsed(
        source: String,
        options: CompactInspectionOptions = CompactInspectionOptions(true),
        ios: Boolean = false,
    ) = Json.parseToJsonElement(options.project(source, ios)).jsonObject
}
