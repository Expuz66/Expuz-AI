package com.expuz.ai.domain

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

data class ProbeResult(
    val reachable: Boolean,
    val httpStatus: Int? = null,
    val latencyMs: Long = 0,
    val failure: FailureKind? = null,
    val technicalMessage: String? = null,
)

interface ServerProbe {
    suspend fun probe(url: String): ProbeResult
}

/**
 * Reachability probe used before saving a server and when suggesting a
 * fallback. It deliberately uses the platform/OkHttp TLS validation; there is
 * no trust-all configuration anywhere in the app.
 */
class OkHttpServerProbe(
    private val client: OkHttpClient = defaultClient(),
) : ServerProbe {

    override suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull()
            ?: return@withContext ProbeResult(
                reachable = false,
                failure = FailureKind.UNKNOWN,
                technicalMessage = "Malformed URL",
            )

        val request = Request.Builder()
            .url(parsed)
            .get()
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.8,*/*;q=0.5")
            .header("User-Agent", USER_AGENT)
            .build()

        val started = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val latency = elapsedMs(started)
                val status = response.code
                if (status >= 500) {
                    return@withContext ProbeResult(
                        reachable = false,
                        httpStatus = status,
                        latencyMs = latency,
                        failure = FailureKind.HTTP_ERROR,
                        technicalMessage = "HTTP $status",
                    )
                }
                ProbeResult(
                    reachable = true,
                    httpStatus = status,
                    latencyMs = latency,
                )
            }
        } catch (error: Throwable) {
            ProbeResult(
                reachable = false,
                latencyMs = elapsedMs(started),
                failure = error.toFailureKind(),
                technicalMessage = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L

    private fun Throwable.toFailureKind(): FailureKind = when (this) {
        is UnknownHostException -> FailureKind.HOST_NOT_FOUND
        is SocketTimeoutException -> FailureKind.TIMEOUT
        is InterruptedIOException -> FailureKind.TIMEOUT
        is SSLHandshakeException -> FailureKind.TLS
        is SSLPeerUnverifiedException -> FailureKind.TLS
        is CertificateException -> FailureKind.TLS
        is ConnectException -> FailureKind.CONNECTION_REFUSED
        is IOException -> {
            val message = message.orEmpty().lowercase()
            when {
                message.contains("cleartext") -> FailureKind.CLEARTEXT_BLOCKED
                message.contains("timeout") -> FailureKind.TIMEOUT
                else -> FailureKind.CONNECTION_REFUSED
            }
        }

        else -> FailureKind.UNKNOWN
    }

    companion object {
        private const val USER_AGENT = "ExpuzAI-Android/1.0 (connection-check)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // Probes are single requests, but the shared client keeps pooled
            // connections. Servers such as uvicorn close idle keep-alive
            // sockets, so a reused dead socket must be retried on a fresh one.
            .retryOnConnectionFailure(true)
            .build()
    }
}
