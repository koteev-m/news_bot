package billing.stars

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import common.runCatchingNonFatal

data class StarsClientConfig(
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val retryMax: Int,
    val retryBaseDelayMs: Long,
)

open class StarsClient(
    private val botToken: String,
    private val config: StarsClientConfig,
    client: HttpClient? = null,
    private val baseUrl: String = "https://api.telegram.org",
): AutoCloseable {

    private val logger = LoggerFactory.getLogger(StarsClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = client ?: defaultClient()

    open suspend fun getBotStarBalance(): BotStarBalance {
        val payload = fetchBalancePayload()
        val available = payload.available ?: payload.amount
        val pending = payload.pending ?: 0L
        val updatedAt = payload.updatedAt ?: nowEpochSeconds()

        if (available == null) {
            throw StarsClientDecodeError(IllegalStateException("telegram response missing balance"))
        }

        return BotStarBalance(
            available = available,
            pending = pending,
            updatedAtEpochSeconds = updatedAt,
        )
    }

    open suspend fun getBotStarAmount(): StarAmount {
        val payload = fetchBalancePayload()
        val amount = payload.amount ?: payload.available
            ?: throw StarsClientDecodeError(IllegalStateException("telegram response missing amount"))

        return StarAmount(
            amount = amount,
            nano = payload.nano,
        )
    }

    private suspend fun fetchBalancePayload(): BalancePayload {
        val started = System.nanoTime()
        val response = httpClient.get("$baseUrl/bot$botToken/getMyStarBalance")

        val latencyMs = (System.nanoTime() - started) / 1_000_000
        logger.debug(
            "stars-client action=getBotStarBalance status={} latencyMs={}",
            response.status.value,
            latencyMs,
        )

        val payload = response.bodyAsText()
        val decoded = runCatchingNonFatal { json.decodeFromString(TgBalanceResponse.serializer(), payload) }.getOrNull()
        val retryAfterSeconds = decoded?.parameters?.retryAfter ?: parseRetryAfter(response.headers[HttpHeaders.RetryAfter])

        if (response.status == HttpStatusCode.TooManyRequests) {
            throw StarsClientRateLimited(retryAfterSeconds)
        }

        if (decoded != null && !decoded.ok) {
            val code = decoded.errorCode
            when {
                code == HttpStatusCode.TooManyRequests.value -> throw StarsClientRateLimited(retryAfterSeconds)
                code != null && code in 500..599 -> throw StarsClientServerError(code)
                code != null && code in 400..499 -> throw StarsClientBadRequest(code)
                else -> throw StarsClientDecodeError(IllegalStateException("telegram response not ok"))
            }
        }

        if (response.status.value in 500..599) {
            throw StarsClientServerError(response.status.value)
        }

        if (response.status.value in 400..499) {
            throw StarsClientBadRequest(response.status.value)
        }

        val result = decoded?.result ?: throw StarsClientDecodeError(IllegalStateException("telegram response not ok"))

        if (result.available == null && result.amount == null) {
            throw StarsClientDecodeError(IllegalStateException("telegram response missing balance fields"))
        }

        return BalancePayload(
            available = result.available,
            pending = result.pending,
            updatedAt = result.updatedAt,
            amount = result.amount,
            nano = result.nanoStarAmount,
        )
    }

    private fun defaultClient(): HttpClient = HttpClient(CIO) {
        expectSuccess = false

        defaultRequest {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
        }

        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMs
            requestTimeoutMillis = config.readTimeoutMs
            socketTimeoutMillis = config.readTimeoutMs
        }

        install(ContentNegotiation) {
            json(json)
        }

        install(HttpRequestRetry) {
            maxRetries = config.retryMax
            retryIf { _, response ->
                when {
                    response.status == HttpStatusCode.TooManyRequests ->
                        parseRetryAfter(response.headers[HttpHeaders.RetryAfter]) != null
                    response.status.value >= 500 -> true
                    else -> false
                }
            }
            retryOnExceptionIf { _, cause -> cause is IOException }
            delayMillis(respectRetryAfterHeader = true) { attempt ->
                exponentialDelay(attempt)
            }
        }
    }

    private fun exponentialDelay(attempt: Int): Long {
        val cappedAttempt = attempt.coerceAtLeast(0)
        val multiplier = 1L shl cappedAttempt
        val maxDelay = config.retryBaseDelayMs * 10
        val baseDelay = maxOf(1L, min(config.retryBaseDelayMs * multiplier, maxDelay))
        val jitter = ThreadLocalRandom.current().nextLong(baseDelay / 10 + 1)
        return baseDelay + jitter
    }

    private fun parseRetryAfter(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(1) }

        return runCatchingNonFatal {
            val date = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val now = Instant.now()
            val diff = Duration.between(now, date).seconds
            diff.coerceAtLeast(1)
        }.getOrNull()
    }

    override fun close() {
        httpClient.close()
    }
}

private const val USER_AGENT = "stars-client"

private fun nowEpochSeconds(): Long = Instant.now().epochSecond

open class StarsClientException(message: String) : Exception(message)
class StarsClientRateLimited(val retryAfterSeconds: Long? = null) : StarsClientException("telegram rate limited")
class StarsClientServerError(val code: Int) : StarsClientException("telegram server error: $code")
class StarsClientBadRequest(val code: Int) : StarsClientException("telegram bad request: $code")
class StarsClientDecodeError(cause: Throwable) : StarsClientException("telegram decode error: ${cause.message}")

@Serializable
private data class TgBalanceResponse(
    val ok: Boolean,
    val result: TgBalancePayload? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
    val parameters: TgErrorParameters? = null,
)

@Serializable
private data class TgBalancePayload(
    @SerialName("available_balance") val available: Long? = null,
    @SerialName("pending_balance") val pending: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val amount: Long? = null,
    @SerialName("nanostar_amount") val nanoStarAmount: Long? = null,
)

@Serializable
private data class TgErrorParameters(
    @SerialName("retry_after") val retryAfter: Long? = null,
)

data class BalancePayload(
    val available: Long?,
    val pending: Long?,
    val updatedAt: Long?,
    val amount: Long?,
    val nano: Long?,
)
