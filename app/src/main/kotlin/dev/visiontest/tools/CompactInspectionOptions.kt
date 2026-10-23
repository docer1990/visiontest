package dev.visiontest.tools

import dev.visiontest.common.CompactInspectionOptions
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun compactInspectionProperties(ios: Boolean): JsonObject = buildJsonObject {
    putJsonObject("compact") {
        put("type", "boolean")
        put("description", "Return compact-v1 JSON (default false)")
    }
    putJsonObject("type") {
        put("type", "string")
        put("description", "Exact native className; requires compact")
    }
    putJsonObject("region") {
        put("type", "string")
        put("description", "Center region x,y,width,height in native units; requires compact")
    }
    putJsonObject("includeBounds") {
        put("type", "boolean")
        put("description", "Retain native bounds and centers; requires compact")
    }
    putJsonObject("includeDisabled") {
        put("type", "boolean")
        put("description", "Include disabled elements (default false)")
    }
    if (ios) putJsonObject("bundleId") {
        put("type", "string")
        put("description", "App bundle ID; omit for Springboard")
    }
}

internal fun CallToolRequest.compactInspectionOptions(): CompactInspectionOptions = CompactInspectionOptions(
    compact = optionalBoolean("compact") ?: false,
    type = optionalString("type"),
    region = optionalString("region"),
    includeBounds = optionalBoolean("includeBounds") ?: false,
)
