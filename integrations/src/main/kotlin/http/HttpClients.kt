package http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryEvent
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

object HttpClients {
    private val currentService: ThreadLocal<String?> = ThreadLocal()

    private class ServiceContextElement(private val service: String?) : ThreadContextElement<String?> {
        override val key: CoroutineContext.Key<ServiceContextElement> get() = Key

        override fun updateThreadContext(context: CoroutineContext): String? {
            val previous = currentService.get()
            currentService.set(service)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
            currentService.set(oldState)
        }

        companion object Key : CoroutineContext.Key<ServiceContextElement>
    }

    private var metricsRef: IntegrationsMetrics? = null

    fun build(
        cfg: IntegrationsHttpConfig,
        metrics: IntegrationsMetrics,
        clock: Clock = Clock.systemUTC(),
        engineFactory: HttpClientEngineFactory<*> = CIO
    ): HttpClient {
        val client = HttpClient(engineFactory) {
            configure(cfg, metrics, clock)
        }
        registerRetryMonitor(client, metrics)
        return client
    }

    internal fun HttpClientConfig<*>.configure(
        cfg: IntegrationsHttpConfig,
        metrics: IntegrationsMetrics,
        clock: Clock
    ) {
        metricsRef = metrics
        val retryCfg = cfg.retry

        expectSuccess = true

        install(HttpTimeout) {
            connectTimeoutMillis = cfg.timeoutMs.connect
            requestTimeoutMillis = cfg.timeoutMs.request
            socketTimeoutMillis = cfg.timeoutMs.socket
        }

        install(DefaultRequest) {
            header(HttpHeaders.UserAgent, cfg.userAgent)
            accept(ContentType.Application.Json)
        }

        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = false
                }
            )
        }

        install(HttpRequestRetry) {
            val maxRetries = (retryCfg.maxAttempts - 1).coerceAtLeast(0)
            this.maxRetries = maxRetries
            retryIf { _, response ->
                retryCfg.retryOn.contains(response.status.value)
            }
            retryOnExceptionIf { _, cause ->
                cause is IOException || cause is HttpRequestTimeoutException || cause is ResponseException
            }
            delayMillis(respectRetryAfterHeader = false) { attempt ->
                computeDelayMillis(this, attempt, retryCfg, metrics, clock)
            }
        }
    }

    internal fun registerRetryMonitor(client: HttpClient, metrics: IntegrationsMetrics) {
        client.monitor.subscribe(HttpRequestRetryEvent) { event ->
            if (event.retryCount <= 0) return@subscribe
            val service = currentService.get() ?: "unknown"
            metrics.retryCounter(service).increment()
        }
    }

    suspend fun <T> measure(service: String, block: suspend () -> T): T {
        val metrics = metricsRef ?: error("HttpClients metrics are not initialized")
        val sample = metrics.timerSample()
        return withContext(ServiceContextElement(service)) {
            try {
                val result = block()
                metrics.stopTimer(sample, service, "success")
                result
            } catch (ex: Throwable) {
                metrics.stopTimer(sample, service, "error")
                throw ex
            }
        }
    }

    private fun computeDelayMillis(
        context: io.ktor.client.plugins.HttpRetryDelayContext,
        attempt: Int,
        retryCfg: RetryCfg,
        metrics: IntegrationsMetrics,
        clock: Clock
    ): Long {
        val service = currentService.get()
        if (retryCfg.respectRetryAfter) {
            val retryAfter = context.response?.headers?.get(HttpHeaders.RetryAfter)
            val headerDelay = retryAfter?.let { parseRetryAfterMillis(it, clock.instant()) }
            if (headerDelay != null) {
                val tag = service ?: "unknown"
                metrics.retryAfterHonored(tag).increment()
                return headerDelay
            }
        }
        val multiplier = 1L shl attempt.coerceAtMost(16)
        val baseDelay = retryCfg.baseBackoffMs * multiplier
        val jitterRange = retryCfg.jitterMs
        val jitter = if (jitterRange > 0) {
            ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1)
        } else {
            0L
        }
        val computed = baseDelay + jitter
        return if (computed < 0L) Long.MAX_VALUE else computed.coerceAtLeast(0L)
    }

    private fun parseRetryAfterMillis(headerValue: String, now: Instant): Long? {
        headerValue.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return 0L
            return TimeUnit.SECONDS.toMillis(seconds)
        }
        return try {
            val retryInstant = ZonedDateTime.parse(headerValue, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val diff = Duration.between(now, retryInstant).toMillis()
            if (diff <= 0) 0L else diff
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

sealed class HttpClientError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    abstract val category: String
    open val metricsTag: String get() = category

    data class HttpStatusError(val status: HttpStatusCode, val requestUrl: String, val origin: Throwable? = null) :
        HttpClientError("HTTP ${status.value} for $requestUrl", origin) {
        override val category: String = "http"
        override val metricsTag: String get() = status.value.toString()
    }

    data class TimeoutError(val requestUrl: String?, val origin: Throwable) :
        HttpClientError("Request timed out for ${requestUrl ?: "unknown"}", origin) {
        override val category: String = "timeout"
    }

    data class NetworkError(val requestUrl: String?, val origin: Throwable) :
        HttpClientError("Network error for ${requestUrl ?: "unknown"}", origin) {
        override val category: String = "network"
    }

    data class DeserializationError(val details: String, val origin: Throwable? = null) :
        HttpClientError("Failed to deserialize response: $details", origin) {
        override val category: String = "deserialization"
    }

    data class UnexpectedError(val requestUrl: String?, val origin: Throwable) :
        HttpClientError("Unexpected error for ${requestUrl ?: "unknown"}", origin) {
        override val category: String = "unexpected"
    }

    data class ValidationError(val details: String) : HttpClientError(details) {
        override val category: String = "validation"
    }
}

internal fun Throwable.toHttpClientError(): HttpClientError = when (this) {
    is HttpClientError -> this
    is HttpRequestTimeoutException -> HttpClientError.TimeoutError(null, this)
    is io.ktor.client.plugins.ResponseException -> {
        val requestUrl = runCatching { response.call.request.url.toString() }.getOrNull() ?: "unknown"
        HttpClientError.HttpStatusError(response.status, requestUrl, this)
    }
    is SerializationException -> HttpClientError.DeserializationError(message ?: "serialization error", this)
    is IOException -> HttpClientError.NetworkError(null, this)
    else -> HttpClientError.UnexpectedError(null, this)
}

typealias HttpResult<T> = Result<T>
