package dev.visiontest.recording

import dev.visiontest.common.RequestDeadline
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

internal fun interface RecordingDataSource {
    suspend fun read(offset: Long, maxBytes: Int, deadline: RequestDeadline): ByteArray
}

internal class ChannelRecordingSource(private val channel: FileChannel) : RecordingDataSource {
    override suspend fun read(offset: Long, maxBytes: Int, deadline: RequestDeadline): ByteArray {
        val buffer = ByteBuffer.allocate(maxBytes)
        while (buffer.hasRemaining()) {
            coroutineContext.ensureActive()
            deadline.remainingMillis()
            if (channel.read(buffer, offset + buffer.position()) < 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }
}
