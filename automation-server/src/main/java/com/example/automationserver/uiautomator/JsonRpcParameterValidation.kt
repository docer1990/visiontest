package com.example.automationserver.uiautomator

import com.google.gson.JsonElement
import com.google.gson.JsonObject

fun requireObjectParams(params: JsonElement?, method: String): JsonObject? = when (params) {
    null -> null
    is JsonObject -> params
    else -> throw IllegalArgumentException("'$method' parameters must be an object")
}
