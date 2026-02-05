package security

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

private const val METRICS_PATH = "/metrics"
private const val DB_HEALTH_PATH = "/health/db"
private const val HEALTH_PATH = "/healthz"

fun Application.installGlobalRateLimit() {
    val config = environment.config.config("security.rateLimit")
    val capacity = config.property("capacity").getString().toInt()
    val refillPerMinute = config.property("refillPerMinute").getString().toInt()
    val burst = config.property("burst").getString().toInt()

    require(capacity > 0) { "security.rateLimit.capacity must be greater than zero" }
    require(refillPerMinute > 0) { "security.rateLimit.refillPerMinute must be greater than zero" }
    require(burst >= 0) { "security.rateLimit.burst must be zero or positive" }

    val buckets = ConcurrentHashMap<String, PerSubjectTokenBucket>()

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path == METRICS_PATH || path == DB_HEALTH_PATH || path == HEALTH_PATH) {
            return@intercept
        }

        val userSubject =
            call
                .principal<JWTPrincipal>()
                ?.payload
                ?.subject
                ?.takeIf { it.isNotBlank() }
        val forwardedIp =
            call.request
                .header(HttpHeaders.XForwardedFor)
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val realIp = call.request.header("X-Real-IP")?.takeIf { it.isNotBlank() }
        val fallbackHost = call.request.host().takeIf { it.isNotBlank() }
        val subject = userSubject ?: forwardedIp ?: realIp ?: fallbackHost ?: "anonymous"

        val bucket =
            buckets.computeIfAbsent(subject) {
                PerSubjectTokenBucket(capacity, refillPerMinute, burst)
            }

        when (val result = bucket.tryConsume()) {
            is RateLimitResult.Denied -> {
                call.response.headers.append(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                call.respondText(
                    text = "{\"error\":\"rate_limited\"}",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.TooManyRequests,
                )
                finish()
            }
            RateLimitResult.Allowed -> Unit
        }
    }
}

private sealed interface RateLimitResult {
    object Allowed : RateLimitResult

    class Denied(
        val retryAfterSeconds: Int,
    ) : RateLimitResult
}

private class PerSubjectTokenBucket(
    capacity: Int,
    refillPerMinute: Int,
    burst: Int,
) {
    private val maxTokens = (capacity + burst).toDouble()
    private val refillPerSecond = (refillPerMinute / 60.0).coerceAtLeast(1.0)

    @Volatile
    private var tokens = maxTokens

    @Volatile
    private var lastRefillNanos = System.nanoTime()

    fun tryConsume(): RateLimitResult {
        synchronized(this) {
            refill()
            if (tokens >= 1.0) {
                tokens -= 1.0
                return RateLimitResult.Allowed
            }

            val needed = 1.0 - tokens
            val seconds =
                if (refillPerSecond <= 0.0) {
                    1.0
                } else {
                    needed / refillPerSecond
                }
            val retryAfter = ceil(seconds).toInt().coerceAtLeast(1)
            return RateLimitResult.Denied(retryAfter)
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0) {
            return
        }

        val tokensToAdd = elapsedSeconds * refillPerSecond
        tokens = min(maxTokens, tokens + tokensToAdd)
        lastRefillNanos = now
    }
}
