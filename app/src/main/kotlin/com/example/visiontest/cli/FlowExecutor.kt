package com.example.visiontest.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class FlowExecutor {
    suspend fun execute(platform: Platform, steps: List<FlowPreparedStep>): FlowReport {
        val results = steps.map { step ->
            val raw = step.execute()
            val result = when (step.kind) {
                FlowStepKind.ACTION -> (Json.parseToJsonElement(raw) as JsonObject)["result"] as JsonObject
                FlowStepKind.SCREENSHOT -> Json.parseToJsonElement(raw) as JsonObject
                FlowStepKind.WAIT -> {
                    val found = Json.parseToJsonElement(inspectionOutput(raw, true,
                        InspectionCommand.FIND_ELEMENT, platform)) as JsonObject
                    buildJsonObject {
                        found.forEach { (key, value) -> put(key, value) }
                        put("condition", step.condition)
                        put("satisfied", found["found"]!!.jsonPrimitive.content.toBoolean() == (step.condition != "gone"))
                    }
                }
            }
            FlowStepReport(step.id, step.command, "succeeded", result = result)
        }
        return FlowReport(platform, true, 0, results)
    }
}
