package com.raju.aiassistant.data.ai

import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }
/** Appended to a reply that the provider cut off because it hit the output-token limit. */
const val TRUNCATED_NOTE = "\n\n(जवाब लंबाई की हद पर कट गया — 'आगे बताओ' कहें।)"

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

fun JsonElement.toRequestBody(): RequestBody = toString().toRequestBody(JSON_MEDIA)

/** Shared HTTPS client: sane timeouts, no logging of bodies or headers (keys stay out of logs). */
fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()

/** Cancellable await: cancelling the coroutine cancels the HTTP call. */
suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** Executes the request and returns (code, body) or a mapped network failure. */
suspend fun OkHttpClient.fetch(request: Request): AiResult<Pair<Int, String>> = try {
    await(request).use { r -> AiResult.Success(r.code to (r.body?.string() ?: "")) }
} catch (e: IOException) {
    ioFailure(e)
}

fun ioFailure(e: IOException): AiResult.Failure = when (e) {
    is SocketTimeoutException -> AiResult.Failure(FailureKind.TIMEOUT, FailureKind.TIMEOUT.userMessage)
    is UnknownHostException -> AiResult.Failure(FailureKind.NETWORK, "No internet connection or DNS failure")
    is InterruptedIOException -> AiResult.Failure(FailureKind.TIMEOUT, FailureKind.TIMEOUT.userMessage)
    else -> AiResult.Failure(FailureKind.NETWORK, "${FailureKind.NETWORK.userMessage}: ${e.message ?: e.javaClass.simpleName}")
}

/** Maps an HTTP error to a typed failure using the provider's error message when present. */
fun httpFailure(code: Int, body: String): AiResult.Failure {
    val detail = extractErrorMessage(body)
    val lower = detail.lowercase()
    val kind = when {
        code == 401 || code == 403 -> FailureKind.INVALID_KEY
        code == 400 && ("api key" in lower || "api_key" in lower || "incorrect key" in lower) -> FailureKind.INVALID_KEY
        code == 429 && ("quota" in lower || "billing" in lower || "credits" in lower) -> FailureKind.QUOTA_EXCEEDED
        code == 429 -> FailureKind.RATE_LIMITED
        code == 408 -> FailureKind.TIMEOUT
        code >= 500 -> FailureKind.SERVER
        else -> FailureKind.UNKNOWN
    }
    val text = buildString {
        append(kind.userMessage).append(" (HTTP ").append(code).append(')')
        if (detail.isNotBlank()) append(": ").append(detail.take(200))
    }
    return AiResult.Failure(kind, text)
}

fun extractErrorMessage(body: String): String {
    val root = runCatching { JsonFormat.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return ""
    val error = root["error"]
    return when {
        error is JsonPrimitive -> error.contentOrNull.orEmpty()
        error is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        else -> (root["message"] as? JsonPrimitive)?.contentOrNull
            ?: (root["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

private val RETRYABLE = setOf(FailureKind.TIMEOUT, FailureKind.NETWORK, FailureKind.SERVER)

/** Retries transient failures (timeout, network, 5xx) with a short backoff. Auth/quota errors are not retried. */
suspend fun <T> withRetry(retries: Int = 1, backoffMs: Long = 1_000, block: suspend () -> AiResult<T>): AiResult<T> {
    var attempt = 0
    while (true) {
        val result = block()
        if (result is AiResult.Failure && result.kind in RETRYABLE && attempt < retries) {
            attempt++
            delay(backoffMs * attempt)
            continue
        }
        return result
    }
}
