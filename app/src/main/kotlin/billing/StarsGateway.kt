package billing

import billing.model.Tier
import io.ktor.client.HttpClient
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
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.math.min

interface StarsGateway {
    /**
     * Создаёт invoice-link для оплаты Stars (XTR).
     * @param tier   – тариф (для label/описания).
     * @param priceXtr – цена в XTR (целые).
     * @param payload  – короткий payload (идемпотентный ключ, ≤ 64 байт).
     * @return Result<invoiceUrl>
     */
    suspend fun createInvoiceLink(tier: Tier, priceXtr: Long, payload: String): Result<String>
}

class TelegramStarsGateway(
    private val botToken: String,
    private val baseUrl: String = "https://api.telegram.org",
    client: HttpClient? = null,
    private val appName: String = "news-bot"
) : StarsGateway {

    private val logger = LoggerFactory.getLogger(TelegramStarsGateway::class.java)
    private val jsonSerializer: Json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = client ?: defaultClient(appName)

    override suspend fun createInvoiceLink(tier: Tier, priceXtr: Long, payload: String): Result<String> {
        require(priceXtr >= 0) { "priceXtr must be non-negative" }
        require(payload.length <= 64) { "payload length must be <= 64" }

        val requestId = UUID.randomUUID().toString()
        val prices = listOf(LabeledPrice(label = tier.name, amount = priceXtr))
        val formParameters = Parameters.build {
            append("title", "Subscription: ${tier.name}")
            append("description", "Subscription tier ${tier.name}")
            append("payload", payload)
            append("currency", "XTR")
            append("prices", pricesJson(prices))
        }

        return runCatching {
            val response: HttpResponse = httpClient.post("$baseUrl/bot$botToken/createInvoiceLink") {
                setBody(FormDataContent(formParameters))
            }
            logger.debug(
                "createInvoiceLink response requestId={} status={}",
                requestId,
                response.status.value
            )
            val body: TgOkResponse<String> = response.body()
            if (body.ok) {
                body.result ?: throw IllegalStateException("Telegram: empty result")
            } else {
                val description = body.description ?: "unknown error"
                throw IllegalStateException("Telegram: $description")
            }
        }.onFailure {
            logger.warn("createInvoiceLink failure requestId={}", requestId)
        }
    }

    @Serializable
    private data class LabeledPrice(val label: String, val amount: Long)

    @Serializable
    private data class TgOkResponse<T>(val ok: Boolean, val result: T? = null, val description: String? = null)

    private fun pricesJson(prices: List<LabeledPrice>): String = jsonSerializer.encodeToString(prices)

    private fun defaultClient(appName: String): HttpClient = HttpClient(CIO) {
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = Duration.ofSeconds(5).toMillis()
            requestTimeoutMillis = Duration.ofSeconds(15).toMillis()
            socketTimeoutMillis = Duration.ofSeconds(15).toMillis()
        }

        install(ContentNegotiation) {
            json(jsonSerializer)
        }

        install(HttpRequestRetry) {
            maxRetries = 3
            retryIf { _, response ->
                response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
            }
            retryOnExceptionIf { _, cause -> cause is IOException }
            delayMillis(respectRetryAfterHeader = true) { attempt ->
                exponentialBackoff(attempt)
            }
        }

        install(ContentEncoding) {
            runCatching {
                val method = this::class.java.methods.firstOrNull { it.name == "gzip" && it.parameterCount == 0 }
                method?.invoke(this)
            }
            runCatching {
                val method = this::class.java.methods.firstOrNull { it.name == "brotli" && it.parameterCount == 0 }
                method?.invoke(this)
            }
        }

        install(DefaultRequest) {
            header(HttpHeaders.UserAgent, "$appName/stars-gw (Ktor)")
        }
    }

    private fun exponentialBackoff(attempt: Int): Long {
        val baseDelay = 300L
        val maxDelay = 2000L
        val multiplier = 1L shl attempt
        val computed = baseDelay * multiplier
        return min(computed, maxDelay)
    }
}

object StarsGatewayFactory {
    fun fromConfig(env: io.ktor.server.application.ApplicationEnvironment, client: io.ktor.client.HttpClient? = null): StarsGateway {
        val token = env.config.property("telegram.botToken").getString()
        return TelegramStarsGateway(botToken = token, client = client)
    }
}
