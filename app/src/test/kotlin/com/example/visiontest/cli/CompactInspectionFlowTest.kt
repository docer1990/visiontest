package com.example.visiontest.cli

import com.example.visiontest.common.CompactInspectionFeatureScenarios
import com.example.visiontest.common.CompactInspectionOptions
import com.example.visiontest.common.CompactInspectionTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactInspectionFlowTest {
    @TestFactory
    fun scenarios() = CompactInspectionFeatureScenarios.tests(
        CompactInspectionTest().scenarioBindings() + CompactInspectionAdaptersTest.scenarioBindings() + mapOf(
            "Return compact observations from a flow" to ::bindings,
        ), requireComplete = true,
    )

    private fun bindings(): Map<String, () -> Unit> {
        var results = emptyList<Triple<Platform, Boolean, JsonObject>>()
        return mapOf(
            "Given a flow with requested interactive-element observations" to {},
            "When the caller enables compact output" to {
                results = Platform.entries.flatMap { platform -> listOf(false, true).map { failed ->
                    FlowObservationFixture(platform).use { fixture ->
                        fixture.respond = { method -> response(when {
                            method == "ui.getInteractiveElements" -> source(platform)
                            failed -> """{"result":{"success":false,"error":"blocked"}}"""
                            else -> """{"result":{"success":true}}"""
                        }) }
                        val scope = if (platform == Platform.Ios) arrayOf("--bundle-id", "example.app")
                            else emptyArray()
                        val report = fixture.run("--step", "press_home", "--observe", "interactive-elements",
                            "--on-failure", "interactive-elements", "--compact", "--type", "Button",
                            "--region", "0,0,20,20", "--include-bounds", *scope)
                        val request = fixture.requests.last().getValue("params").jsonObject
                        if (platform == Platform.Ios) {
                            assertEquals("example.app", request["bundleId"]?.jsonPrimitive?.content)
                        }
                        assertEquals(listOf("device.pressHome", "ui.getInteractiveElements"), fixture.calls)
                        Triple(platform, failed, report)
                    }
                } }
            },
            "Then final observations and failure evidence use the shared compact representation" to {
                results.forEach { (platform, failed, report) ->
                    assertEquals((!failed).toString(), report["success"].toString())
                    assertEquals(if (failed) "1" else "0", report["exitCode"].toString())
                    val step = report.getValue("steps").jsonArray.single().jsonObject
                    assertEquals(if (failed) "failed" else "succeeded", step["status"]?.jsonPrimitive?.content)
                    assertEquals("confirmed", step["certainty"]?.jsonPrimitive?.content)
                    if (failed) {
                        assertEquals("blocked", report.getValue("error").jsonObject["message"]?.jsonPrimitive?.content)
                    }
                    val observation = report.getValue(if (failed) "evidence" else "observations")
                        .jsonArray.single().jsonObject
                    assertEquals("succeeded", observation["status"]?.jsonPrimitive?.content)
                    val expected = CompactInspectionOptions(true, "Button", "0,0,20,20", true)
                        .project(source(platform), platform == Platform.Ios)
                    assertEquals(Json.parseToJsonElement(expected), observation["result"])
                }
            },
        )
    }

    @Test fun `invalid compact flow options fail before components or backend access`() {
        val invalid = listOf(arrayOf("--compact"), arrayOf("--type", "Button"), arrayOf("--include-bounds"),
            arrayOf("--region", "0,0,1,1"), arrayOf("--compact", "--type", " "),
            arrayOf("--compact", "--region", "bad"))
        val cases = Platform.entries.flatMap { platform -> invalid.withIndex().map { platform to it } }
        for ((platform, indexed) in cases) {
            val (index, options) = indexed
            FlowObservationFixture(platform).use { fixture ->
                val observation = if (index == 0) emptyArray() else arrayOf("--observe", "interactive-elements")
                val result = fixture.runResult("--step", "press_home", *observation, *options)
                assertEquals(2, result.exitCode)
                assertFalse(fixture.components.isInitialized())
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun `compact text flow keeps the normal report and full mode keeps native elements`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method -> response(if (method == "ui.getInteractiveElements") source(platform)
                else """{"result":{"success":true}}""") }
            val text = fixture.runResult("--step", "press_home", "--observe", "interactive-elements", "--compact")
            assertEquals(0, text.exitCode)
            assertTrue(text.stdout.orEmpty().startsWith("Flow succeeded:"))
            assertTrue(text.stdout.orEmpty().contains("compact-v1"))
            val full = fixture.run("--step", "press_home", "--observe", "interactive-elements")
            assertEquals(Json.parseToJsonElement(source(platform)).jsonObject["result"],
                full.getValue("observations").jsonArray.single().jsonObject["result"])
        }
    }

    private fun response(body: String) = MockResponse().setBody(body)

    @Test fun `compact collection failures preserve backend errors and completed action certainty`() {
        val failures = listOf(
            """{"success":false,"count":0,"elements":[],"error":"inspection blocked"}""",
            """{"error":{"code":-32000,"message":"rpc blocked","data":{"reason":"denied"}}}""",
        )
        val cases = Platform.entries.flatMap { platform -> failures.map { platform to it } }
        for ((platform, failure) in cases) FlowObservationFixture(platform).use { fixture ->
            val wire = if (Json.parseToJsonElement(failure).jsonObject.containsKey("success"))
                """{"result":$failure}""" else failure
            fixture.respond = { method -> response(if (method == "ui.getInteractiveElements") wire
                else """{"result":{"success":true}}""") }
            val report = fixture.run("--step", "press_home", "--observe", "interactive-elements", "--compact")
            assertEquals("1", report["exitCode"].toString())
            val observation = report.getValue("observations").jsonArray.single().jsonObject
            assertEquals("failed", observation["status"]?.jsonPrimitive?.content)
            assertEquals(Json.parseToJsonElement(failure), observation["result"])
            val step = report.getValue("steps").jsonArray.single().jsonObject
            assertEquals("succeeded", step["status"]?.jsonPrimitive?.content)
            assertEquals("confirmed", step["certainty"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `compact malformed failure evidence preserves original action failure`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { method -> response(if (method == "ui.getInteractiveElements")
                """{"result":{"success":true,"count":1,"elements":[{"className":42}]}}"""
                else """{"result":{"success":false,"error":"action blocked"}}""") }
            val report = fixture.run("--step", "press_home", "--on-failure", "interactive-elements", "--compact")
            assertEquals("1", report["exitCode"].toString())
            assertEquals("action blocked", report.getValue("error").jsonObject["message"]?.jsonPrimitive?.content)
            val evidence = report.getValue("evidence").jsonArray.single().jsonObject
            assertEquals("failed", evidence["status"]?.jsonPrimitive?.content)
            val message = evidence.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
            assertTrue(message.contains("className"))
        }
    }

    private fun source(platform: Platform): String {
        val bounds = if (platform == Platform.Android) "\"[0,0][20,20]\"" else "\"{{0,0},{20,20}}\""
        return """{"result":{"success":true,"count":3,"elements":[
            {"resourceId":"save","text":"Save","className":"Button","centerX":10,"centerY":10,"bounds":$bounds},
            {"resourceId":"outside","className":"Button","centerX":20,"centerY":10},
            {"resourceId":"label","className":"Label","centerX":10,"centerY":10}]}}"""
    }
}
