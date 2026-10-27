package dev.visiontest.daemon

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Internal transport framing; never write these frames to operation stdout. */
object DaemonFrames {
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    fun read(input: InputStream): JsonObject? {
        val first = input.read()
        if (first == -1) return null
        val data = DataInputStream(input)
        val header = byteArrayOf(first.toByte(), data.readByte(), data.readByte(), data.readByte())
        val length = ByteBuffer.wrap(header).int
        if (length !in 1..MAX_FRAME_BYTES) throw IOException("Invalid daemon frame length: $length")
        val payload = ByteArray(length)
        data.readFully(payload)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return parse(decoder.decode(ByteBuffer.wrap(payload)).toString())
    }

    private fun parse(text: String): JsonObject {
        DaemonJsonSyntax.validate(text)
        return try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw IOException("Daemon frame must contain a JSON object")
        } catch (error: SerializationException) {
            throw IOException("Invalid daemon JSON frame", error)
        }
    }

    fun write(output: OutputStream, value: JsonObject) {
        val text = value.toString()
        DaemonJsonSyntax.validate(text)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        if (encoded.remaining() !in 1..MAX_FRAME_BYTES) throw IOException("Daemon frame exceeds $MAX_FRAME_BYTES bytes")
        val payload = ByteArray(encoded.remaining()).also { encoded.get(it) }
        DataOutputStream(output).apply { writeInt(payload.size); write(payload); flush() }
    }
}
