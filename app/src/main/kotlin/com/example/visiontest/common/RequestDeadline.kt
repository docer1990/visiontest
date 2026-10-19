package com.example.visiontest.common

import kotlinx.coroutines.suspendCancellableCoroutine
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

internal class RequestDeadline(timeoutMs: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestDeadline> {
        private val client: HttpClient by lazy {
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build()
        }
        private val readMethods = setOf("ui.findElement", "ui.getInteractiveElements", "ui.getDeviceInfo",
            "ui.getUiHierarchy", "ui.screenshot")
    }

    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var actionDispatched: Boolean = false
        private set

    fun remainingMillis(): Long {
        val remaining = deadline - System.nanoTime()
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
        return suspendCancellableCoroutine { continuation ->
            if (body != null && method !in readMethods) actionDispatched = true
            val future = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, failure ->
                if (failure == null) continuation.resume(response)
                else continuation.resumeWithException(
                    if (failure is CompletionException) failure.cause ?: failure else failure
                )
            }
        }
    }
}
