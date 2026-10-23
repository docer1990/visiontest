package dev.visiontest.recording

import dev.visiontest.common.RequestDeadline
import kotlinx.coroutines.Deferred
import java.nio.file.Path

internal data class RecordingExit(val exitCode: Int?, val diagnostics: String)

internal interface RecordingSession {
    // Only premature exits complete this notification, including those detected during finish().
    val terminal: Deferred<RecordingExit>
    suspend fun start(deadline: RequestDeadline)
    suspend fun finish(deadline: RequestDeadline): Path
}
