package http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import kotlin.math.pow

fun defaultHttpClient(appName: String): HttpClient = HttpClient(CIO) {
    configureDefaultHttpClient(appName)
}

internal fun defaultHttpClient(appName: String, engineFactory: HttpClientEngineFactory<*>): HttpClient =
    HttpClient(engineFactory) {
        configureDefaultHttpClient(appName)
    }

internal fun HttpClientConfig<*>.configureDefaultHttpClient(appName: String) {
    expectSuccess = true

    install(HttpTimeout) {
        connectTimeoutMillis = Duration.ofSeconds(5).toMillis()
        requestTimeoutMillis = Duration.ofSeconds(15).toMillis()
        socketTimeoutMillis = Duration.ofSeconds(15).toMillis()
    }

    install(HttpRequestRetry) {
        maxRetries = 3
        retryIf(maxRetries) { _, response ->
            response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
        }
        retryOnExceptionIf { _, cause -> cause is IOException }
        delayMillis(respectRetryAfterHeader = false) { attempt ->
            val headerDelay = response?.headers?.get(HttpHeaders.RetryAfter)?.let { parseRetryAfterMillis(it) }
            headerDelay ?: calculateExponentialDelay(attempt)
        }
    }

    install(ContentNegotiation) {
        json(
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            }
        )
    }

    install(ContentEncoding)
    install(HttpCache)
    install(Logging) {
        logger = Logger.SIMPLE
        level = LogLevel.INFO
    }

    install(DefaultRequest) {
        header(HttpHeaders.UserAgent, "$appName (Ktor)")
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
    is ResponseException -> {
        val requestUrl = runCatching { response.call.request.url.toString() }.getOrNull() ?: "unknown"
        HttpClientError.HttpStatusError(response.status, requestUrl, this)
    }
    is SerializationException -> HttpClientError.DeserializationError(message ?: "serialization error", this)
    is IOException -> HttpClientError.NetworkError(null, this)
    else -> HttpClientError.UnexpectedError(null, this)
}

private const val REQUEST_COUNTER = "integrations.http.client.requests"
private const val ERROR_COUNTER = "integrations.http.client.errors"
private const val DURATION_TIMER = "integrations.http.client.duration"

internal class HttpMetricsRecorder(
    private val registry: MeterRegistry,
    private val clientName: String,
    loggerName: String
) {
    private val logger = LoggerFactory.getLogger(loggerName)

    suspend fun <T> record(operation: String, block: suspend () -> T): T {
        registry.counter(REQUEST_COUNTER, "client", clientName, "operation", operation).increment()
        val sample = Timer.start(registry)
        return try {
            val result = block()
            sample.stop(timer(operation, "success"))
            result
        } catch (t: Throwable) {
            if (t is CancellationException) {
                sample.stop(timer(operation, "cancelled"))
                throw t
            }
            val error = t.toHttpClientError()
            sample.stop(timer(operation, "failure"))
            registry.counter(
                ERROR_COUNTER,
                "client",
                clientName,
                "operation",
                operation,
                "code",
                error.metricsTag
            ).increment()
            logger.warn("{} request '{}' failed: {}", clientName, operation, error.message)
            throw error
        }
    }

    private fun timer(operation: String, outcome: String): Timer = registry.timer(
        DURATION_TIMER,
        "client",
        clientName,
        "operation",
        operation,
        "outcome",
        outcome
    )
}

typealias HttpResult<T> = Result<T>

private fun parseRetryAfterMillis(headerValue: String): Long? {
    headerValue.toLongOrNull()?.let { seconds ->
        return TimeUnit.SECONDS.toMillis(seconds.coerceAtLeast(0))
    }
    return try {
        val retryInstant = ZonedDateTime.parse(headerValue, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        val now = Instant.now()
        val diff = Duration.between(now, retryInstant).toMillis()
        if (diff > 0) diff else 0L
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun calculateExponentialDelay(attempt: Int): Long {
    val base = 200.0
    return (base * 2.0.pow((attempt - 1).coerceAtLeast(0))).toLong()
}
