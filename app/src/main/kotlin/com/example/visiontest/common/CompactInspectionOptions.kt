package com.example.visiontest.common

import com.example.visiontest.CommandExecutionException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class CompactInspectionOptions(
    val compact: Boolean = false,
    val type: String? = null,
    val region: String? = null,
    val includeBounds: Boolean = false,
) {
    fun validate() {
        require(compact || (type == null && region == null && !includeBounds)) {
            "type, region and includeBounds require compact"
        }
        require(type == null || type.isNotBlank()) { "type must not be blank" }
        region?.let(InspectionRegion::parse)
    }

    fun project(response: String, ios: Boolean): String {
        validate()
        return if (compact) projectCompact(response, ios) else response
    }

    private fun projectCompact(response: String, ios: Boolean): String {
        val parsed = automationResult(response)
        if (parsed.isRpcError) return parsed.value.toString()
        val result = parsed.value
        result.validateFields("success", FieldType.BOOLEAN, required = true)
        result.validateFields("error", FieldType.STRING)
        val success = result.getValue("success").jsonPrimitive.boolean
        result.validateFields("count", FieldType.INTEGER, required = true)
        val elements = validatedElements(result, ios)
        return if (success) projectElements(elements, ios) else result.toString()
    }

    private fun projectElements(elements: List<JsonObject>, ios: Boolean): String {
        val rectangle = region?.let(InspectionRegion::parse)
        if (rectangle != null) elements.forEach {
            it.validateFields("centerX centerY", FieldType.INTEGER, required = true)
        }
        val matches = selectorMatchCounts(elements, ios)
        val selected = elements.filter {
            (type == null || it["className"]?.jsonPrimitive?.content == type) &&
                (rectangle == null || rectangle.contains(it))
        }
        return buildJsonObject {
            put("success", true)
            put("format", "compact-v1")
            put("count", selected.size)
            put("observedCount", elements.size)
            put("elements", JsonArray(selected.map { compactElement(it, ios, includeBounds, matches) }))
        }.toString()
    }
}

private fun validatedElements(result: JsonObject, ios: Boolean): List<JsonObject> {
    val elements = result["elements"] as? JsonArray
        ?: throw CommandExecutionException("Automation result.elements must be an array")
    return elements.map {
        val element = it as? JsonObject
            ?: throw CommandExecutionException("Automation result.elements entries must be objects")
        validateElement(element, ios, interactive = true)
        element
    }
}

private data class InspectionRegion(val x: Int, val y: Int, val right: Int, val bottom: Int) {
    fun contains(element: JsonObject): Boolean {
        val centerX = element.getValue("centerX").jsonPrimitive.int
        val centerY = element.getValue("centerY").jsonPrimitive.int
        return centerX >= x && centerX < right && centerY >= y && centerY < bottom
    }

    companion object {
        fun parse(value: String): InspectionRegion {
            val parts = value.split(',').map { it.trim().toIntOrNull() }
            require(parts.size == REGION_COMPONENTS && parts.all { it != null }) {
                "region must contain four integers: x,y,width,height"
            }
            val coordinates = parts.map { checkNotNull(it) }.iterator()
            val x = coordinates.next()
            val y = coordinates.next()
            val width = coordinates.next()
            val height = coordinates.next()
            require(x >= 0 && y >= 0 && width > 0 && height > 0) {
                "region x/y must be nonnegative and width/height positive"
            }
            val right = x.toLong() + width
            val bottom = y.toLong() + height
            require(right <= Int.MAX_VALUE && bottom <= Int.MAX_VALUE) { "region edges must not exceed Int.MAX_VALUE" }
            return InspectionRegion(x, y, right.toInt(), bottom.toInt())
        }
    }
}

private const val REGION_COMPONENTS = 4
