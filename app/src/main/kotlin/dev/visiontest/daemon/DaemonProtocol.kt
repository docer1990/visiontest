package dev.visiontest.daemon

import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Identity is explicit in the handshake, even for two development builds of the same release. */
data class DaemonIdentity(val protocol: Int, val appVersion: String, val build: String)

sealed interface DaemonMessage {
    data class Hello(val identity: DaemonIdentity) : DaemonMessage
    data class Ready(val identity: DaemonIdentity) : DaemonMessage
    data class Execute(
        val requestId: String,
        val arguments: List<String>,
        val cwd: String,
        val environment: Map<String, String>,
        val loggingProperties: Map<String, String>,
    ) : DaemonMessage
    data class StdinRequest(val requestId: String) : DaemonMessage
    data class Input(val requestId: String, val data: String, val eof: Boolean) : DaemonMessage
    data class Output(val requestId: String, val stream: String, val data: String) : DaemonMessage
    data class Completion(val requestId: String, val exitCode: Int) : DaemonMessage
    data class Cancel(val requestId: String) : DaemonMessage
    data class Status(val details: JsonObject? = null) : DaemonMessage
    data class Stop(val accepted: Boolean? = null) : DaemonMessage
}

object DaemonProtocol {
    const val VERSION = 1
    const val MAX_CHUNK_BYTES = 64 * 1024

    fun requireCompatible(local: DaemonIdentity, remote: DaemonIdentity, execution: Boolean) {
        validateIdentity(local)
        validateIdentity(remote)
        if (local.protocol != remote.protocol) {
            throw IOException("Incompatible daemon protocol; stop it using its original installation before restarting")
        }
        if (execution && local != remote) {
            throw IOException(
                "Incompatible daemon application build; run 'visiontest daemon stop' then retry explicitly",
            )
        }
    }

    fun encode(message: DaemonMessage): JsonObject {
        val value = buildJsonObject {
            when (message) {
                is DaemonMessage.Hello -> { put("kind", "hello"); identity(message.identity) }
                is DaemonMessage.Ready -> { put("kind", "ready"); identity(message.identity) }
                is DaemonMessage.Execute -> {
                    put("kind", "execute"); put("requestId", message.requestId)
                    put("arguments", JsonArray(message.arguments.map(::JsonPrimitive)))
                    put("cwd", message.cwd)
                    put("environment", stringMap(message.environment))
                    put("loggingProperties", stringMap(message.loggingProperties))
                }
                is DaemonMessage.StdinRequest -> { put("kind", "stdin-request"); put("requestId", message.requestId) }
                is DaemonMessage.Input -> {
                    put("kind", "input"); put("requestId", message.requestId)
                    put("data", message.data); put("eof", message.eof)
                }
                is DaemonMessage.Output -> {
                    if (message.stream !in listOf("stdout", "stderr")) fail("Invalid output stream")
                    put("kind", message.stream); put("requestId", message.requestId); put("data", message.data)
                }
                is DaemonMessage.Completion -> {
                    put("kind", "completion"); put("requestId", message.requestId); put("exitCode", message.exitCode)
                }
                is DaemonMessage.Cancel -> { put("kind", "cancel"); put("requestId", message.requestId) }
                is DaemonMessage.Status -> { put("kind", "status"); message.details?.let { put("details", it) } }
                is DaemonMessage.Stop -> { put("kind", "stop"); message.accepted?.let { put("accepted", it) } }
            }
        }
        decode(value)
        return value
    }

    fun decode(value: JsonObject): DaemonMessage {
        if (value.toString().toByteArray().size > DaemonFrames.MAX_FRAME_BYTES) fail("Message exceeds input limit")
        return when (val kind = value.string("kind")) {
            "hello" -> DaemonMessage.Hello(identity(value))
            "ready" -> DaemonMessage.Ready(identity(value))
            "execute" -> execute(value)
            "stdin-request" -> DaemonMessage.StdinRequest(value.requestId())
            "input" -> DaemonMessage.Input(
                value.requestId(), value.data(DaemonFrames.MAX_FRAME_BYTES), value.bool("eof"),
            )
            "stdout", "stderr" -> DaemonMessage.Output(value.requestId(), kind, value.data(MAX_CHUNK_BYTES))
            "completion" -> DaemonMessage.Completion(value.requestId(), value.integer("exitCode"))
            "cancel" -> DaemonMessage.Cancel(value.requestId())
            "status" -> decodeStatus(value)
            "stop" -> decodeStop(value)
            else -> fail("Unknown daemon message kind: $kind")
        }
    }

    /** Byte chunks preserve UTF-8 even when a code point crosses chunk boundaries. Decode after joining. */
    fun outputChunks(requestId: String, stream: String, bytes: ByteArray): List<DaemonMessage.Output> {
        if (stream !in listOf("stdout", "stderr")) fail("Invalid output stream")
        return (bytes.indices step MAX_CHUNK_BYTES).map { start ->
            val chunk = bytes.copyOfRange(start, minOf(start + MAX_CHUNK_BYTES, bytes.size))
            DaemonMessage.Output(requestId, stream, Base64.getEncoder().encodeToString(chunk))
        }
    }

    internal fun identity(value: JsonObject): DaemonIdentity =
        DaemonIdentity(value.integer("protocol"), value.string("appVersion"), value.string("build"))
            .also(::validateIdentity)

    private fun execute(value: JsonObject): DaemonMessage.Execute {
        val cwd = value.string("cwd")
        try {
            if (!Path.of(cwd).isAbsolute) fail("Caller directory must be absolute")
        } catch (error: InvalidPathException) {
            throw IOException("Invalid caller directory", error)
        }
        val arguments = value["arguments"] as? JsonArray ?: fail("Invalid arguments")
        val environment = value.map("environment")
        environment.forEach { (key, entry) ->
            val invalidKey = key.isEmpty() || '=' in key || '\u0000' in key
            if (invalidKey || '\u0000' in entry) fail("Invalid process environment")
        }
        return DaemonMessage.Execute(
            value.requestId(), arguments.map { stringValue(it, "argument") }, cwd,
            environment, value.map("loggingProperties"),
        )
    }

    private fun validateIdentity(identity: DaemonIdentity) {
        val invalidVersion = identity.appVersion.isBlank() || identity.appVersion.length > MAX_VERSION_LENGTH
        if (identity.protocol <= 0 || invalidVersion || !identity.build.matches(Regex("[a-f0-9]{64}"))) {
            fail("Invalid daemon identity")
        }
    }

    private fun decodeStop(value: JsonObject) =
        DaemonMessage.Stop(if ("accepted" in value) value.bool("accepted") else null)

    private fun decodeStatus(value: JsonObject) = DaemonMessage.Status(
        value["details"]?.let { it as? JsonObject ?: fail("Invalid status details") },
    )

}

/** Overflow means an infrastructure failure after potential dispatch, never a successful truncated result. */
class DaemonOutputBudget(private val maximum: Long = DEFAULT_OUTPUT_LIMIT) {
    private var used = 0L
    fun accept(bytes: Int) {
        if (bytes < 0 || bytes.toLong() > maximum - used) {
            throw IOException("Daemon output limit exceeded; dispatch outcome may be uncertain; do not replay")
        }
        used += bytes
    }
}

private fun JsonObjectBuilder.identity(identity: DaemonIdentity) {
    put("protocol", identity.protocol); put("appVersion", identity.appVersion); put("build", identity.build)
}
private fun stringMap(values: Map<String, String>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
private fun JsonObject.map(key: String): Map<String, String> =
    (get(key) as? JsonObject ?: fail("Invalid $key")).mapValues { stringValue(it.value, key) }
private fun JsonObject.string(key: String) = stringValue(get(key), key)
private fun stringValue(value: JsonElement?, key: String): String =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("Invalid $key string")
private fun JsonObject.integer(key: String): Int =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail("Invalid $key integer")
private fun JsonObject.bool(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: fail("Invalid $key boolean")
private fun JsonObject.requestId() = string("requestId").also {
    if (it.isBlank() || it.length > MAX_REQUEST_ID_LENGTH) fail("Invalid request identity")
}
private fun JsonObject.data(limit: Int): String = string("data").also {
    val bytes = try { Base64.getDecoder().decode(it) } catch (error: IllegalArgumentException) {
        throw IOException("Invalid base64 payload", error)
    }
    if (bytes.size > limit) fail("Payload exceeds $limit bytes")
}
private fun fail(message: String): Nothing = throw IOException(message)

private const val MAX_REQUEST_ID_LENGTH = 128
private const val MAX_VERSION_LENGTH = 128
private const val DEFAULT_OUTPUT_LIMIT = 67108864L
