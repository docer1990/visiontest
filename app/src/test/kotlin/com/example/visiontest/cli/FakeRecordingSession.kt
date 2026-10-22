package com.example.visiontest.cli

import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.recording.RecordingExit
import com.example.visiontest.recording.RecordingSession
import com.example.visiontest.recording.movieWithMetadataBeforeVideo
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.Path

internal class FakeRecordingSession(private val artifacts: Path) : RecordingSession {
    val events: MutableList<String> = mutableListOf()
    override val terminal = CompletableDeferred<RecordingExit>()
    var startFailure: Exception? = null
    var finishFailure: Exception? = null
    var beforeFinish: suspend () -> Unit = {}
    var platform: Platform = Platform.Android

    override suspend fun start(deadline: RequestDeadline) {
        deadline.remainingMillis()
        events += "record-start"
        startFailure?.let { throw it }
    }

    override suspend fun finish(deadline: RequestDeadline): Path {
        events += "record-stop"
        beforeFinish()
        deadline.remainingMillis()
        finishFailure?.let { throw it }
        Files.createDirectories(artifacts)
        val name = if (platform == Platform.Ios) "recording.mov" else "recording.mp4"
        return Files.write(artifacts.resolve(name), movieWithMetadataBeforeVideo()).toAbsolutePath()
    }
}
