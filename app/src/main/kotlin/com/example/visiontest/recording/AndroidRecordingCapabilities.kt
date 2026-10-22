package com.example.visiontest.recording

internal class AndroidRecordingCapabilities private constructor(private val maximumSeconds: Long?) {
    fun durationSeconds(requiredDurationMs: Long): Long {
        val maximum = maximumSeconds ?: invalidCapability("Android recording duration capability is unknown")
        if (requiredDurationMs <= 0) invalidCapability("Android recording duration must be positive")
        val seconds = requiredDurationMs / MILLIS_PER_SECOND +
            if (requiredDurationMs % MILLIS_PER_SECOND == 0L) 0 else 1
        if (seconds > maximum) {
            invalidCapability("Android recording requires $seconds seconds; supported maximum is $maximum seconds")
        }
        return seconds
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private val maximum = Regex("Maximum(?:\\s*/\\s*default)?\\s+is\\s+([0-9]+)\\.(?:\\s|$)",
            RegexOption.IGNORE_CASE)

        fun parse(help: String): AndroidRecordingCapabilities {
            if (!help.contains("--time-limit")) return AndroidRecordingCapabilities(null)
            val bound = if (help.contains("Set to 0 to remove the time limit.", ignoreCase = true)) {
                Int.MAX_VALUE.toLong()
            } else {
                maximum.find(help)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            }
            return AndroidRecordingCapabilities(bound)
        }
    }
}

private fun invalidCapability(message: String): Nothing = throw RecordingException(message)
