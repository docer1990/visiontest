package com.example.visiontest.cli

import com.example.visiontest.common.automationResult
import com.example.visiontest.common.validateFindElementResult
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun waitOutput(response: String, json: Boolean, platform: Platform, gone: Boolean): String {
    val result = automationResult(response).value
    validateFindElementResult(result, platform == Platform.Ios)
    val condition = if (gone) "gone" else "appeared"
    return if (json) {
        buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("condition", condition)
            put("satisfied", result.getValue("found").jsonPrimitive.boolean == !gone)
        }.toString()
    } else {
        "Element $condition."
    }
}
