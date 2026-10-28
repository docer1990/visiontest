package dev.visiontest.common

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Request-local transport evidence, independent of operation result text or exit codes. */
internal class DispatchEvidence(private val admitMutation: (String) -> Unit = {}) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DispatchEvidence> {
        private val readMethods = setOf("ui.findElement", "ui.getInteractiveElements", "device.getInfo",
            "ui.dumpHierarchy", "ui.screenshot")
    }
    @Volatile var platform = "unknown"
    @Volatile var pending = false
        private set
    @Volatile var uncertain = false
        private set
    @Volatile var transportFailed = false
        private set
    fun submitting(method: String?) { if (method !in readMethods) submittingMutation() }
    fun submittingMutation() { admitMutation(platform); pending = true }
    fun confirmed(method: String?) { if (method !in readMethods) pending = false }
    fun received(method: String, response: String) {
        if (method in readMethods) return
        try {
            val result = automationResult(response)
            if (!result.isRpcError) {
                result.value.validateFields("success", FieldType.BOOLEAN, required = true)
                result.value.validateFields("error", FieldType.STRING)
            }
            confirmed(method)
        } catch (_: dev.visiontest.CommandExecutionException) {
            failed()
        }
    }
    fun failed() { transportFailed = true; if (pending) uncertain = true }
    fun operationFailed() { if (pending) failed() }
}

/** Capture before registrar/CLI failure mapping; process completion is distinct from native cancellation. */
@Suppress("TooGenericExceptionCaught") // Preserve every failure while recording uncertainty before it is mapped.
internal suspend fun <T> trackHostMutation(block: suspend () -> T): T {
    val evidence = kotlin.coroutines.coroutineContext[DispatchEvidence]
    evidence?.submittingMutation()
    return try {
        block().also { evidence?.confirmed("host.mutation") }
    } catch (error: Exception) {
        evidence?.failed()
        throw error
    }
}
