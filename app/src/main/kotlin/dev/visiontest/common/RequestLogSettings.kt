package dev.visiontest.common

import org.slf4j.event.Level
import java.text.SimpleDateFormat
import java.util.Date

/** Caller-specific SimpleLogger settings, with the packaged application defaults. */
internal class RequestLogSettings(private val properties: Map<String, String>) {
    fun enabled(name: String, level: Level): Boolean {
        var category = name
        var value: String? = null
        while (value == null && category.isNotEmpty()) {
            value = property("log.$category")
            category = category.substringBeforeLast('.', "")
        }
        val threshold = when ((value ?: property("defaultLogLevel") ?: "warn").lowercase()) {
            "trace" -> Level.TRACE.toInt()
            "debug" -> Level.DEBUG.toInt()
            "info" -> Level.INFO.toInt()
            "warn" -> Level.WARN.toInt()
            "error" -> Level.ERROR.toInt()
            "off" -> Int.MAX_VALUE
            else -> Level.INFO.toInt()
        }
        return level.toInt() >= threshold
    }

    fun format(name: String, level: Level, message: String?, startedAtMillis: Long): String = buildString {
        if (boolean("showDateTime", false)) {
            val timestamp = property("dateTimeFormat")?.let { pattern ->
                runCatching { SimpleDateFormat(pattern).format(Date()) }.getOrNull()
            } ?: (System.currentTimeMillis() - startedAtMillis).toString()
            append(timestamp).append(' ')
        }
        if (boolean("showThreadName", false)) append('[').append(Thread.currentThread().name).append("] ")
        if (boolean("showThreadId", false)) append("tid=").append(Thread.currentThread().id).append(' ')
        val label = if (level == Level.WARN) property("warnLevelString") ?: level.toString() else level.toString()
        if (boolean("levelInBrackets", false)) append('[').append(label).append(']') else append(label)
        append(' ')
        if (boolean("showShortLogName", true)) append(name.substringAfterLast('.')).append(" - ")
        else if (boolean("showLogName", true)) append(name).append(" - ")
        append(message)
    }

    private fun boolean(name: String, default: Boolean): Boolean = property(name)?.toBoolean() ?: default
    private fun property(name: String): String? = properties["org.slf4j.simpleLogger.$name"]
}
