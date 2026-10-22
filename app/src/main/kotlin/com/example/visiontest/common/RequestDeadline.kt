package com.example.visiontest.common

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext

internal class RequestDeadline(timeoutMs: Long, private val nowNs: () -> Long = System::nanoTime) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestDeadline> {
        private val client: HttpClient by lazy {
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build()
        }
        private val readMethods = setOf("ui.findElement", "ui.getInteractiveElements", "device.getInfo",
            "ui.dumpHierarchy", "ui.screenshot")
    }

    private val deadline = nowNs() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var actionDispatched: Boolean = false
        private set

    fun remainingMillis(): Long {
        val remaining = deadline - nowNs()
        if (remaining <= 0) throw TimeoutException("Flow execution deadline exceeded")
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
    }

    suspend fun exchange(
        url: String,
        body: ByteArray?,
        method: String? = null,
        timeoutMs: Int,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(minOf(remainingMillis(), timeoutMs.toLong())))
        headers.forEach { (name, value) -> request.header(name, value) }
        if (body == null) request.GET() else {
            request.header("Content-Type", "application/json")
            request.POST(HttpRequest.BodyPublishers.ofByteArray(body))
        }
        // HttpRequest.timeout may stop applying once headers arrive; bound the complete body as well.
        // withTimeoutOrNull converts only this timeout, preserving cancellation from the caller.
        val httpClient = client
        coroutineContext.ensureActive()
        return withTimeoutOrNull(minOf(remainingMillis(), timeoutMs.toLong())) {
            suspendCancellableCoroutine { continuation ->
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val prepared = request.build()
                continuation.context.ensureActive()
                remainingMillis()
                if (body != null && method !in readMethods) actionDispatched = true
                val future = httpClient.sendAsync(prepared, HttpResponse.BodyHandlers.ofByteArray())
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { response, failure ->
                    if (failure == null) continuation.resume(response)
                    else continuation.resumeWithException(
                        if (failure is CompletionException) failure.cause ?: failure else failure
                    )
                }
            }
        } ?: throw TimeoutException("Flow HTTP exchange deadline exceeded")
    }
}
