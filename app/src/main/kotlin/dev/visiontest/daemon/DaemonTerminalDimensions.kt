package dev.visiontest.daemon

import dev.visiontest.cli.CliTerminalDimensions
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun JsonObjectBuilder.terminalDimensions(dimensions: CliTerminalDimensions?) {
    dimensions?.let {
        put("terminal", buildJsonObject {
            put("width", it.width); put("height", it.height)
            it.outputInteractive?.let { interactive -> put("interactive", interactive) }
        })
    }
}
