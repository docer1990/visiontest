package com.example.visiontest.cli

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FlowWaitResponseTest {
    @Test
    fun `invalid unmatched poll responses fail immediately before another request`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) for (fields in invalidFields(platform)) {
            assertInvalidPoll(platform, gone, fields)
        }
    }

    private fun invalidFields(platform: Platform): List<String> =
        listOf("\"extra\":foo", "\"text\":42", "\"isEnabled\":\"true\"") +
            if (platform == Platform.Android) listOf("\"isClickable\":42") else listOf("\"value\":42")

    private fun assertInvalidPoll(platform: Platform, gone: Boolean, fields: String) {
        FlowObservationFixture(platform).use { fixture ->
            var polls = 0
            fixture.respond = {
                polls++
                val result = if (polls == 1) """{"found":$gone,$fields}"""
                    else """{"found":${!gone}}"""
                MockResponse().setBody("""{"result":$result}""")
            }
            val report = fixture.run("--step", waitStep(gone), "--step", "press_home")
            assertEquals("false", report.getValue("success").toString(), "$platform $gone $fields")
            assertEquals("1", report.getValue("exitCode").toString())
            assertEquals("step-1", report.getValue("failedStep").jsonPrimitive.content)
            assertEquals("skipped", report.getValue("steps").jsonArray[1].jsonObject
                .getValue("status").jsonPrimitive.content)
            assertEquals(1, polls)
            assertEquals(listOf("ui.findElement"), fixture.calls)
        }
    }

    @Test
    fun `valid additive platform fields remain accepted while polling`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) assertValidPoll(platform, gone)
    }

    private fun assertValidPoll(platform: Platform, gone: Boolean) {
        FlowObservationFixture(platform).use { fixture ->
            var polls = 0
            fixture.respond = {
                polls++
                val found = if (polls == 1) gone else !gone
                val fields = if (platform == Platform.Android) "\"isClickable\":true,\"value\":42"
                    else "\"value\":\"Done\",\"isClickable\":42"
                MockResponse().setBody("""{"result":{"found":$found,$fields,"extra":[null,1,true]}}""")
            }
            val report = fixture.run("--step", waitStep(gone))
            assertEquals("true", report.getValue("success").toString())
            assertEquals(2, polls)
        }
    }

    private fun waitStep(gone: Boolean) = "wait_for_element --text Done" + if (gone) " --gone" else ""
}
