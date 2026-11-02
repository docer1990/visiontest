package dev.visiontest.common

import dev.visiontest.cli.CliExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter

/** A retained logger routes dynamically; it never captures a caller or changes process streams. */
internal class RequestLogger(private val delegate: Logger) : LegacyAbstractLogger() {
    init { name = delegate.name }

    private fun enabled(level: Level, fallback: () -> Boolean): Boolean =
        CliExecutionContext.currentOrNull()?.logSettings?.enabled(name, level) ?: fallback()
    override fun isTraceEnabled() = enabled(Level.TRACE) { delegate.isTraceEnabled }
    override fun isDebugEnabled() = enabled(Level.DEBUG) { delegate.isDebugEnabled }
    override fun isInfoEnabled() = enabled(Level.INFO) { delegate.isInfoEnabled }
    override fun isWarnEnabled() = enabled(Level.WARN) { delegate.isWarnEnabled }
    override fun isErrorEnabled() = enabled(Level.ERROR) { delegate.isErrorEnabled }
    override fun getFullyQualifiedCallerName(): String = RequestLogger::class.java.name

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val request = CliExecutionContext.currentOrNull()
        if (request == null) {
            delegate.atLevel(level).apply {
                if (marker != null) addMarker(marker)
                setCause(throwable)
                arguments?.forEach { addArgument(it) }
            }.log(messagePattern)
            return
        }
        val formatted = MessageFormatter.arrayFormat(messagePattern, arguments, throwable)
        val markedMessage = marker?.let { " ${it.name} ${formatted.message}" } ?: formatted.message
        val message = request.logSettings.format(name, level, markedMessage, request.loggingStartedAtMillis)
        val failure = throwable ?: formatted.throwable
        val details = failure?.let { "\n${it.stackTraceToString().trimEnd()}" } ?: ""
        (request.logOutput ?: request.diagnostic)(message + details)
    }

    companion object {
        fun getLogger(name: String): Logger = RequestLogger(LoggerFactory.getLogger(name))
        fun getLogger(type: Class<*>): Logger = RequestLogger(LoggerFactory.getLogger(type))
    }
}
