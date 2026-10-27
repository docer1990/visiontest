package dev.visiontest.daemon

import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.performance.TraceRecorder
import dev.visiontest.performance.TraceRuntime
import java.io.File

/** Retained graph ownership. The host serializes whole requests before acquiring or invalidating. */
internal class DaemonComponents(
    private val factory: () -> ComponentHolder = {
        ComponentHolder.createDefault(TraceRecorder.Contextual, registerShutdownHook = false)
    },
    private val targetGuard: DaemonTargetGuard = DaemonTargetGuard(),
) : AutoCloseable {
    private var holder: ComponentHolder? = null
    private var configuration: Map<String, String>? = null
    private var closed = false
    private var target: DaemonTargetGuard.Target? = null
    var initializationCount: Int = 0
        private set

    @Synchronized
    fun acquire(context: CliExecutionContext): ComponentHolder = acquire(context, null)

    /** Only real construction contributes a component.init interval to this request's trace. */
    @Synchronized
    fun acquire(context: CliExecutionContext, trace: TraceRuntime?): ComponentHolder {
        check(!closed) { "Daemon components are closed" }
        val next = configuration(context)
        if (configuration != next) invalidate()
        if (trace != null) {
            val platform = dev.visiontest.cli.Platform.entries.single { it.value == trace.platform }
            var admittedSuccessfully = false
            val admitted = try {
                trace.inExecutionContext { targetGuard.check(platform, checkNotNull(trace.operation)) }
                    .also { admittedSuccessfully = true }
            } finally {
                if (!admittedSuccessfully) invalidate()
            }
            if (admitted != null) {
                if (target != null && target != admitted) invalidate()
                target = admitted
            }
        }
        holder?.let { return it }
        val created = context.withCurrent {
            if (trace == null) factory() else trace.initializeComponents(factory)
        }
        holder = created
        configuration = next
        initializationCount++
        return created
    }

    /** Called only after active execution settles, including transport failure or target changes. */
    @Synchronized
    fun invalidate() {
        val previous = holder
        holder = null
        configuration = null
        target = null
        previous?.close()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        invalidate()
    }

    private fun configuration(context: CliExecutionContext): Map<String, String> =
        context.environment.filterKeys { key ->
            key in TOOL_ENVIRONMENT || key in ARTIFACT_PATHS || key.startsWith("ANDROID_") || key.startsWith("ADB_")
        }.filter { (key, value) -> key != "VISIONTEST_DIR" || value.isNotBlank() }
            .mapValues { (key, value) ->
                when {
                    key == "PATH" -> value.split(File.pathSeparatorChar)
                        .joinToString(File.pathSeparator) { context.resolve(it).toString() }
                    key in ARTIFACT_PATHS ->
                        context.resolve(if (key == "VISIONTEST_DIR") value.trim() else value).toString()
                    else -> value
                }
            }

    private companion object {
        val TOOL_ENVIRONMENT = setOf("PATH", "HOME", "TMPDIR", "DEVELOPER_DIR", "SDKROOT", "TOOLCHAINS")
        val ARTIFACT_PATHS = setOf("VISIONTEST_DIR", "VISION_TEST_APK_PATH", "VISION_TEST_IOS_PROJECT_PATH")
    }
}
