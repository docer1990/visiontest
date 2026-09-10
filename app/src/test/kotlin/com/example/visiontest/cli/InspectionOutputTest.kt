package com.example.visiontest.cli

import com.example.visiontest.CommandExecutionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InspectionOutputTest {
    private val cases = Platform.entries.flatMap { platform ->
        val version = if (platform == Platform.Android) "\"sdkVersion\":35" else "\"osVersion\":\"26.5\""
        listOf(
            Case(InspectionCommand.DEVICE_INFO, platform, objectOf(
                """{"success":false,"displayWidth":1080,"displayHeight":2400,
                    "displayRotation":0,"productName":"Phone",$version}"""
            ), objectOf("""{"error":"failed"}""")),
            Case(InspectionCommand.FIND_ELEMENT, platform, objectOf("""{"found":false}"""),
                elementFields(platform, interactive = false) + ("error" to JsonPrimitive("failed"))),
            Case(InspectionCommand.INTERACTIVE_ELEMENTS, platform,
                objectOf("""{"success":false,"count":0,"elements":[]}"""), objectOf("""{"error":"failed"}"""))
        )
    }

    @Test
    fun `each command accepts documented minima and preserves extra fields`() {
        for (case in cases) {
            assertOutput(case, case.required)
            assertOutput(case, case.required + case.optional)
            assertOutput(case, case.required + ("future" to objectOf("""{"nested":[null,true,42]}""")))
        }
    }

    @Test
    fun `every required field must be present even on operation failure`() {
        for (case in cases) for (field in case.required.keys) {
            assertInvalid(case, case.required - field, "Missing $field")
        }
    }

    @Test
    fun `required and optional fields reject all incompatible JSON types`() {
        for (case in cases) for ((field, valid) in case.required + case.optional) {
            for (invalid in incompatibleValues(valid)) {
                assertInvalid(case, case.required + (field to invalid), "$field=$invalid")
            }
        }
    }

    @Test
    fun `interactive elements allow absent fields and preserve documented and extra fields`() {
        for (case in cases.filter { it.command == InspectionCommand.INTERACTIVE_ELEMENTS }) {
            val fields = elementFields(case.platform, interactive = true)
            val elements = JsonArray(
                listOf(JsonObject(emptyMap()), JsonObject(fields + ("future" to JsonPrimitive(7))))
            )
            assertOutput(case, case.required + ("elements" to elements))
            for ((field, valid) in fields) for (invalid in incompatibleValues(valid)) {
                val badElements = JsonArray(listOf(JsonObject(mapOf(field to invalid))))
                assertInvalid(case, case.required + ("elements" to badElements), "element.$field=$invalid")
            }
            for (invalid in listOf("null", "1", "true", "\"element\"", "[]")) {
                assertInvalid(case, case.required + ("elements" to Json.parseToJsonElement("[$invalid]")), invalid)
            }
        }
    }

    @Test
    fun `platform specific fields are validated only on their platform`() {
        for (case in cases) {
            val extra = if (case.platform == Platform.Android) "value" else "isClickable"
            if (case.command == InspectionCommand.FIND_ELEMENT) {
                assertOutput(case, case.required + (extra to JsonPrimitive(42)))
            }
        }
    }

    @Test
    fun `valid RPC errors preserve fields and bypass result schemas`() {
        val error = """{"code":-32601,"message":"Unknown method","data":{"detail":null},"future":true}"""
        for (case in cases) {
            val response = """{"jsonrpc":"2.0","id":17,"error":$error}"""
            assertEquals("""{"error":$error}""", output(case, response))
        }
    }

    @Test
    fun `malformed envelopes and invalid RPC errors fail`() {
        val invalidErrors = listOf("null", "[]", "true", "{}", """{"code":1}""", """{"message":"bad"}""",
            """{"code":"1","message":"bad"}""", """{"code":1.5,"message":"bad"}""",
            """{"code":null,"message":"bad"}""", """{"code":true,"message":"bad"}""",
            """{"code":1,"message":null}""", """{"code":1,"message":42}""",
            """{"code":1,"message":false}""")
        val invalidBodies = listOf("not json", "[]", "null", "{}", """{"result":null}""",
            """{"result":[]}""", """{"result":true}""", """{"result":1}""",
            """{"result":{},"error":{"code":1,"message":"bad"}}""") +
            invalidErrors.map { """{"error":$it}""" }
        for (case in cases) for (body in invalidBodies) {
            assertFailsWith<CommandExecutionException>(body) { output(case, body) }
        }
    }

    @Test
    fun `integer fields accept signed 32 bit boundaries and reject overflow`() {
        for (case in cases.filter { it.command == InspectionCommand.DEVICE_INFO }) {
            for (boundary in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertOutput(case, case.required + ("displayWidth" to JsonPrimitive(boundary)))
            }
            for (overflow in listOf("-2147483649", "2147483648")) {
                assertInvalid(case, case.required + ("displayWidth" to Json.parseToJsonElement(overflow)), overflow)
            }
        }
    }

    @Test
    fun `text output is returned verbatim without validation`() {
        for (case in cases) for (body in listOf("not json", "{\"result\":{}}")) {
            assertEquals(body, inspectionOutput(body, false, case.command, case.platform))
        }
    }

    private fun elementFields(platform: Platform, interactive: Boolean): Map<String, JsonElement> {
        val fields = objectOf("""{"text":"Login","resourceId":"login","className":"Button",
            "contentDescription":"Login","bounds":"[1,2][3,4]","isEnabled":true}""").toMutableMap()
        if (platform == Platform.Android) fields["isClickable"] = JsonPrimitive(false)
        else fields["value"] = JsonPrimitive("value")
        if (interactive) {
            fields["centerX"] = JsonPrimitive(1)
            fields["centerY"] = JsonPrimitive(2)
            if (platform == Platform.Android) {
                for (field in listOf("isCheckable", "isScrollable", "isLongClickable")) {
                    fields[field] = JsonPrimitive(false)
                }
            }
        }
        return fields
    }

    private fun incompatibleValues(valid: JsonElement): List<JsonElement> {
        val candidates = listOf("null", "{}", "[]", "true", "1", "1.5", "\"true\"", "\"1\"")
        val excluded = when {
            valid is JsonArray -> setOf("[]")
            valid is JsonPrimitive && valid.isString -> setOf("\"true\"", "\"1\"")
            valid.toString() in listOf("true", "false") -> setOf("true")
            else -> setOf("1")
        }
        return (candidates - excluded).map(Json::parseToJsonElement)
    }

    private fun assertOutput(case: Case, fields: Map<String, JsonElement>) {
        val result = JsonObject(fields)
        assertEquals(result.toString(), output(case, """{"result":$result,"error":null}"""))
    }

    private fun assertInvalid(case: Case, fields: Map<String, JsonElement>, reason: String) {
        assertFailsWith<CommandExecutionException>("${case.command} ${case.platform}: $reason") {
            output(case, """{"result":${JsonObject(fields)}}""")
        }
    }

    private fun output(case: Case, response: String) = inspectionOutput(response, true, case.command, case.platform)
    private fun objectOf(json: String) = Json.parseToJsonElement(json).jsonObject

    private data class Case(
        val command: InspectionCommand,
        val platform: Platform,
        val required: JsonObject,
        val optional: Map<String, JsonElement>
    )
}
