package billing.stars

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

data class StarsClientConfig(
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val retryMax: Int,
    val retryBaseDelayMs: Long,
)

class StarsClient(
    private val botToken: String,
    private val config: StarsClientConfig,
    client: HttpClient? = null,
    private val baseUrl: String = "https://api.telegram.org",
) {

    private val logger = LoggerFactory.getLogger(StarsClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = client ?: defaultClient()

    suspend fun getMyStarBalance(userId: Long): StarBalance {
        val started = System.nanoTime()
        val response = httpClient.post("$baseUrl/bot$botToken/getMyStarBalance") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("user_id" to userId))
        }

        val latencyMs = (System.nanoTime() - started) / 1_000_000
        logger.info(
            "stars-client action=getMyStarBalance status={} latencyMs={}",
            response.status.value,
            latencyMs,
        )

        if (response.status == HttpStatusCode.TooManyRequests) {
            throw StarsClientRateLimited()
        }

        if (response.status.value in 500..599) {
            throw StarsClientServerError(response.status.value)
        }

        if (response.status.value in 400..499) {
            throw StarsClientBadRequest(response.status.value)
        }

        val payload = response.bodyAsText()
        val decoded = runCatching { json.decodeFromString(TgBalanceResponse.serializer(), payload) }
            .getOrElse { throw StarsClientDecodeError(it) }

        if (!decoded.ok || decoded.result == null) {
            throw StarsClientDecodeError(IllegalStateException("telegram response not ok"))
        }

        return StarBalance(
            userId = userId,
            available = decoded.result.available,
            pending = decoded.result.pending,
            updatedAtEpochSeconds = decoded.result.updatedAt,
        )
    }

    private fun defaultClient(): HttpClient = HttpClient(CIO) {
        expectSuccess = false

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
                response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
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
        return min(config.retryBaseDelayMs * multiplier, maxDelay)
    }
}

open class StarsClientException(message: String) : Exception(message)
class StarsClientRateLimited : StarsClientException("telegram rate limited")
class StarsClientServerError(val code: Int) : StarsClientException("telegram server error: $code")
class StarsClientBadRequest(val code: Int) : StarsClientException("telegram bad request: $code")
class StarsClientDecodeError(cause: Throwable) : StarsClientException("telegram decode error: ${cause.message}")

@Serializable
private data class TgBalanceResponse(
    val ok: Boolean,
    val result: TgBalancePayload? = null,
)

@Serializable
private data class TgBalancePayload(
    @SerialName("available_balance") val available: Long,
    @SerialName("pending_balance") val pending: Long,
    @SerialName("updated_at") val updatedAt: Long,
)
