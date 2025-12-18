@file:Suppress("unused")

package netflow2

import http.CircuitBreaker
import http.HttpClientError
import http.HttpClients
import http.HttpResult
import http.IntegrationsMetrics
import http.RetryCfg
import io.ktor.client.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

class Netflow2Client(
    private val client: HttpClient,
    private val circuitBreaker: CircuitBreaker,
    private val metrics: IntegrationsMetrics,
    private val retryCfg: RetryCfg,
    private val clock: Clock = Clock.systemUTC(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        metrics.requestTimer(SERVICE, "success")
        metrics.requestTimer(SERVICE, "error")
    }

    suspend fun fetchWindow(ticker: String, window: Netflow2PullWindow): HttpResult<List<Netflow2Row>> {
        if (ticker.isBlank()) {
            return Result.failure(HttpClientError.ValidationError("ticker must not be blank"))
        }
        val (from, till) = window.toMoexQueryParams()
        return runResilient {
            // The actual HTTP call will be implemented in the ingestion task.
            Result.failure(
                HttpClientError.ValidationError(
                    "Netflow2 client is not implemented yet for %s-%s".format(from, till),
                ),
            )
        }
    }

    fun windowsSequence(fromInclusive: LocalDate, tillExclusive: LocalDate): Sequence<Netflow2PullWindow> =
        Netflow2PullWindow.split(fromInclusive, tillExclusive).asSequence()

    private suspend fun <T> runResilient(block: suspend () -> HttpResult<T>): HttpResult<T> {
        var attempt = 0
        var lastError: Throwable? = null
        val deadline = clock.instant().plus(requestTimeout)
        while (attempt < max(1, retryCfg.maxAttempts)) {
            try {
                return circuitBreaker.withPermit {
                    HttpClients.measure(SERVICE) { block() }
                }
            } catch (ex: Throwable) {
                lastError = ex
                attempt += 1
                if (clock.instant().isAfter(deadline)) {
                    throw HttpClientError.TimeoutError(null, IllegalStateException("Netflow2 deadline exceeded", ex))
                }
                if (attempt >= retryCfg.maxAttempts) {
                    break
                }
                metrics.retryCounter(SERVICE).increment()
                delay(computeBackoff(attempt))
            }
        }
        throw lastError ?: IllegalStateException("Netflow2Client failed without throwable")
    }

    private fun computeBackoff(attempt: Int): Long {
        val multiplier = 1L shl min(attempt - 1, 16)
        val jitter = if (retryCfg.jitterMs > 0) {
            ThreadLocalRandom.current().nextLong(-retryCfg.jitterMs, retryCfg.jitterMs + 1)
        } else {
            0L
        }
        val delayed = retryCfg.baseBackoffMs * multiplier + jitter
        return delayed.coerceAtLeast(0L)
    }

    companion object {
        private const val SERVICE = "netflow2"
    }
}
