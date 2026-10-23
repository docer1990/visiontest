package dev.visiontest.common

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Versioned synthetic observations exercise the production projection and export its exact payloads. */
class CompactInspectionEvaluationTest {
    @Test
    fun `versioned observations preserve expected targets and failures`() {
        val fixtures = File("../benchmarks/compact-inspection/v1").listFiles()
            ?.filter { it.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assertEquals(8, fixtures.size, "Both platforms need form, navigation, ambiguous and failure fixtures")
        val records = fixtures.map { file ->
            val fixture = Json.parseToJsonElement(file.readText()).jsonObject
            val source = fixture.getValue("full").jsonObject
            val full = source.toString()
            val compact = CompactInspectionOptions(compact = true).project(
                buildJsonObject { put("result", source) }.toString(),
                ios = fixture.getValue("platform").jsonPrimitive.content == "ios",
            )
            val result = Json.parseToJsonElement(compact).jsonObject
            if (source.getValue("success").jsonPrimitive.boolean) {
                assertTargets(fixture, result)
            } else {
                assertEquals(source, result, "Acquisition failures must remain visible")
            }
            buildJsonObject {
                put("id", fixture.getValue("id"))
                put("full", full)
                put("compact", compact)
            }
        }
        val output = File("build/reports/compact-inspection/payloads.json")
        assertTrue(output.parentFile.mkdirs() || output.parentFile.isDirectory)
        output.writeText(JsonArray(records).toString())
    }

    private fun assertTargets(fixture: JsonObject, result: JsonObject) {
        val expected = fixture.getValue("expected").jsonArray
        val actual = result.getValue("elements").jsonArray
        assertEquals(expected.size, actual.size, "No target may disappear")
        assertEquals(expected.size, result.getValue("count").jsonPrimitive.int)
        assertEquals(expected.size, result.getValue("observedCount").jsonPrimitive.int)
        expected.zip(actual).forEach { (target, element) ->
            target.jsonObject.forEach { (key, value) ->
                assertEquals(value, element.jsonObject[key], "${fixture["id"]}: target $target field $key")
            }
        }
    }
}
