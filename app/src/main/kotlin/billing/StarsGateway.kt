package billing

import billing.model.Tier
import billing.port.StarsGateway
import common.runCatchingNonFatal
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.UUID
import kotlin.math.min

class TelegramStarsGateway(
    private val botToken: String,
    private val baseUrl: String = "https://api.telegram.org",
    client: HttpClient? = null,
    private val appName: String = "news-bot",
) : StarsGateway {
    private val logger = LoggerFactory.getLogger(TelegramStarsGateway::class.java)
    private val jsonSerializer: Json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = client ?: defaultClient(appName)

    override suspend fun createInvoiceLink(
        tier: Tier,
        priceXtr: Long,
        payload: String,
    ): Result<String> {
        require(priceXtr >= 0) { "priceXtr must be non-negative" }
        require(payload.length <= MAX_PAYLOAD_LENGTH) { "payload length must be <= $MAX_PAYLOAD_LENGTH" }

        val requestId = UUID.randomUUID().toString()
        val prices = listOf(LabeledPrice(label = tier.name, amount = priceXtr))
        val formParameters =
            Parameters.build {
                append("title", "Subscription: ${tier.name}")
                append("description", "Subscription tier ${tier.name}")
                append("payload", payload)
                append("currency", "XTR")
                append("prices", pricesJson(prices))
            }

        return runCatchingNonFatal {
            val response: HttpResponse =
                httpClient.post("$baseUrl/bot$botToken/createInvoiceLink") {
                    setBody(FormDataContent(formParameters))
                }
            logger.debug(
                "createInvoiceLink response requestId={} status={}",
                requestId,
                response.status.value,
            )
            val body: TgOkResponse<String> = response.body()
            if (body.ok) {
                body.result ?: error("Telegram: empty result")
            } else {
                val description = body.description ?: "unknown error"
                error("Telegram: $description")
            }
        }.onFailure {
            logger.warn("createInvoiceLink failure requestId={}", requestId)
        }
    }

    @Serializable
    private data class LabeledPrice(
        val label: String,
        val amount: Long,
    )

    @Serializable
    private data class TgOkResponse<T>(
        val ok: Boolean,
        val result: T? = null,
        val description: String? = null,
    )

    private fun pricesJson(prices: List<LabeledPrice>): String = jsonSerializer.encodeToString(prices)

    private fun defaultClient(appName: String): HttpClient =
        HttpClient(CIO) {
            expectSuccess = false

            install(HttpTimeout) {
                connectTimeoutMillis = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS).toMillis()
                requestTimeoutMillis = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS).toMillis()
                socketTimeoutMillis = Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS).toMillis()
            }

            install(ContentNegotiation) {
                json(jsonSerializer)
            }

            install(HttpRequestRetry) {
                maxRetries = MAX_RETRIES
                retryIf { _, response ->
                    response.status == HttpStatusCode.TooManyRequests ||
                        response.status.value >= HTTP_SERVER_ERROR_START
                }
                retryOnExceptionIf { _, cause -> cause is IOException }
                delayMillis(respectRetryAfterHeader = true) { attempt ->
                    exponentialBackoff(attempt)
                }
            }

            install(ContentEncoding) {
                runCatchingNonFatal {
                    val method = this::class.java.methods.firstOrNull { it.name == "gzip" && it.parameterCount == 0 }
                    method?.invoke(this)
                }
                runCatchingNonFatal {
                    val method = this::class.java.methods.firstOrNull { it.name == "brotli" && it.parameterCount == 0 }
                    method?.invoke(this)
                }
            }

            install(DefaultRequest) {
                header(HttpHeaders.UserAgent, "$appName/stars-gw (Ktor)")
            }
        }

    private fun exponentialBackoff(attempt: Int): Long {
        val multiplier = 1L shl attempt
        val computed = BACKOFF_BASE_DELAY_MS * multiplier
        return min(computed, BACKOFF_MAX_DELAY_MS)
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val REQUEST_TIMEOUT_SECONDS = 15L
        private const val SOCKET_TIMEOUT_SECONDS = 15L
        private const val MAX_RETRIES = 3
        private const val HTTP_SERVER_ERROR_START = 500
        private const val BACKOFF_BASE_DELAY_MS = 300L
        private const val BACKOFF_MAX_DELAY_MS = 2000L
    }
}

object StarsGatewayFactory {
    fun fromConfig(
        env: io.ktor.server.application.ApplicationEnvironment,
        client: io.ktor.client.HttpClient? = null,
    ): StarsGateway {
        val token = env.config.property("telegram.botToken").getString()
        return TelegramStarsGateway(botToken = token, client = client)
    }
}

private const val MAX_PAYLOAD_LENGTH = 64
