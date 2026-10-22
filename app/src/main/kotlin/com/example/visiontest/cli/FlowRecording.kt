package com.example.visiontest.cli

import com.example.visiontest.recording.AndroidRecordingSession
import com.example.visiontest.recording.IosRecordingSession
import com.example.visiontest.recording.JvmRecordingProcessRunner
import com.example.visiontest.recording.RecordingSession
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

internal data class FlowRecordingReport(
    val status: String,
    val phase: String? = null,
    val path: String? = null,
    val error: FlowError? = null,
)

internal fun interface FlowRecordingFactory {
    fun create(scope: CoroutineScope, platform: Platform, artifacts: Path, requiredDurationMs: Long): RecordingSession
}

internal data class FlowRecordingOptions(val artifacts: Path, val factory: Lazy<FlowRecordingFactory>)

internal class SystemFlowRecordingFactory : FlowRecordingFactory {
    override fun create(
        scope: CoroutineScope,
        platform: Platform,
        artifacts: Path,
        requiredDurationMs: Long,
    ): RecordingSession = when (platform) {
        Platform.Android -> AndroidRecordingSession(scope, artifacts, requiredDurationMs, JvmRecordingProcessRunner())
        Platform.Ios -> IosRecordingSession(scope, artifacts, JvmRecordingProcessRunner())
    }
}
