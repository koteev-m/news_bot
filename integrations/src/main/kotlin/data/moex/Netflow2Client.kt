package data.moex

import http.CircuitBreaker
import http.HttpClientError
import http.HttpClients
import http.HttpResult
import http.IntegrationsMetrics
import http.RetryCfg
import http.toHttpClientError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import netflow2.Netflow2PullWindow
import netflow2.normalizeTicker
import netflow2.Netflow2Row
import common.runCatchingNonFatal

class Netflow2Client(
    private val client: HttpClient,
    private val circuitBreaker: CircuitBreaker,
    private val metrics: IntegrationsMetrics,
    private val retryCfg: RetryCfg,
    private val config: Netflow2ClientConfig = Netflow2ClientConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    init {
        metrics.requestTimer(SERVICE, "success")
        metrics.requestTimer(SERVICE, "error")
    }

    suspend fun fetchWindow(ticker: String, window: Netflow2PullWindow): HttpResult<List<Netflow2Row>> {
        val normalizedTicker = try {
            normalizeTicker(ticker)
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            return Result.failure(Netflow2ClientError.ValidationError(ex.message ?: "invalid ticker", ex))
        }

        val (from, tillInclusive) = try {
            window.toMoexQueryParams()
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            return Result.failure(
                Netflow2ClientError.ValidationError(
                    ex.message ?: "invalid window for Netflow2",
                    ex
                )
            )
        }

        val endpoint = buildEndpoint(normalizedTicker)
        return runResilient(endpoint) {
            val response = try {
                client.get(endpoint) {
                    parameter("from", from)
                    parameter("till", tillInclusive)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Error) {
                throw err
            } catch (ex: ResponseException) {
                val payload = readBodyOrNull(ex.response)
                throw HttpClientError.httpStatusError(ex.response.status, endpoint, payload, origin = ex)
            }

            val payload = response.bodyAsText()
            ensureSuccess(response, endpoint, payload)

            when (config.format) {
                Netflow2Format.CSV -> parseCsv(payload, normalizedTicker)
                Netflow2Format.JSON -> parseJson(payload, normalizedTicker)
            }
        }
    }

    fun windowsSequence(fromInclusive: LocalDate, tillExclusive: LocalDate): Sequence<Netflow2PullWindow> =
        Netflow2PullWindow.split(fromInclusive, tillExclusive).asSequence()

    fun windowsSequence(range: ClosedRange<LocalDate>): Sequence<Netflow2PullWindow> =
        windowsSequence(range.start, range.endInclusive.plusDays(1))

    private fun buildEndpoint(ticker: String): String {
        val base = config.baseUrl.trim().removeSuffix("/")
        val extension = when (config.format) {
            Netflow2Format.CSV -> "csv"
            Netflow2Format.JSON -> "json"
        }
        return "$base$NETFLOW_PATH$ticker.$extension"
    }

    private fun ensureSuccess(response: HttpResponse, url: String, snippet: String? = null) {
        if (response.status.isSuccess()) return
        throw HttpClientError.httpStatusError(response.status, url, snippet)
    }

    private fun parseCsv(payload: String, expectedTicker: String): List<Netflow2Row> {
        val lines = payload.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()

        val headerIndex = lines.indexOfFirst { it.isHeaderLine() }
        if (headerIndex < 0) {
            throw Netflow2ClientError.UpstreamError("Netflow2 CSV header is missing (DATE/TRADEDATE + SECID/TICKER)")
        }

        val header = lines[headerIndex]
            .split(';')
            .map { cleanCsvToken(it).uppercase(Locale.ROOT) }
        val index = header.withIndex().associate { it.value to it.index }
        val dateIdx = index["DATE"] ?: index["TRADEDATE"] ?: throw Netflow2ClientError.UpstreamError(
            "DATE/TRADEDATE column is missing in Netflow2 CSV"
        )
        val tickerIdx = index["SECID"] ?: index["TICKER"] ?: throw Netflow2ClientError.UpstreamError(
            "SECID/TICKER column is missing in Netflow2 CSV"
        )

        val dataLines = lines.drop(headerIndex + 1)
        if (dataLines.isEmpty()) return emptyList()

        return dataLines.mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val columns: List<String?> = line.split(';')
            try {
                val ticker = columns.getOrNull(tickerIdx)?.let(::cleanCsvToken)?.ifBlank { null }?.let(::normalizeTicker)
                    ?: expectedTicker
                val date = columns.getOrNull(dateIdx)?.let(::cleanCsvToken)?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                    ?: throw Netflow2ClientError.UpstreamError("Missing date column in Netflow2 CSV line: $line")

                Netflow2Row(
                    date = date,
                    ticker = ticker,
                    p30 = columns.longAt(index["P30"]),
                    p70 = columns.longAt(index["P70"]),
                    p100 = columns.longAt(index["P100"]),
                    pv30 = columns.longAt(index["PV30"]),
                    pv70 = columns.longAt(index["PV70"]),
                    pv100 = columns.longAt(index["PV100"]),
                    vol = columns.longAt(index["VOL"]),
                    oi = columns.longAt(index["OI"])
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Error) {
                throw err
            } catch (ex: Throwable) {
                throw Netflow2ClientError.UpstreamError(
                    "Failed to parse Netflow2 CSV line: $line",
                    ex
                )
            }
        }
    }

    private fun parseJson(payload: String, expectedTicker: String): List<Netflow2Row> {
        val root = try {
            json.parseToJsonElement(payload)
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            throw Netflow2ClientError.UpstreamError("Unable to parse Netflow2 JSON payload", ex)
        }

        val dataset = extractDataset(root) ?: return emptyList()

        val columns = dataset["columns"] as? JsonArray
            ?: throw Netflow2ClientError.UpstreamError("Netflow2 JSON payload has no columns section")
        val rows = dataset["data"] as? JsonArray ?: return emptyList()

        val index = columns.withIndex().associate { it.value.jsonPrimitive.content.uppercase(Locale.ROOT) to it.index }
        val dateIdx = index["DATE"] ?: index["TRADEDATE"] ?: throw Netflow2ClientError.UpstreamError(
            "DATE/TRADEDATE column is missing in Netflow2 JSON"
        )
        val tickerIdx = index["SECID"] ?: index["TICKER"] ?: throw Netflow2ClientError.UpstreamError(
            "SECID/TICKER column is missing in Netflow2 JSON"
        )

        return rows.mapNotNull { rowElement ->
            val row = (rowElement as? JsonArray)?.map { it.jsonPrimitive.contentOrNull?.trim() } ?: return@mapNotNull null
            try {
                val ticker = row.getOrNull(tickerIdx)?.takeIf { !it.isNullOrBlank() }?.let(::normalizeTicker)
                    ?: expectedTicker
                val dateRaw = row.getOrNull(dateIdx)?.takeIf { !it.isNullOrBlank() }
                    ?: throw Netflow2ClientError.UpstreamError("Missing date column in Netflow2 JSON row: $row")
                val date = try {
                    LocalDate.parse(dateRaw)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Error) {
                    throw err
                } catch (parseEx: Throwable) {
                    throw Netflow2ClientError.UpstreamError("Invalid date '$dateRaw' in Netflow2 JSON", parseEx)
                }

                Netflow2Row(
                    date = date,
                    ticker = ticker,
                    p30 = row.longAt(index["P30"]),
                    p70 = row.longAt(index["P70"]),
                    p100 = row.longAt(index["P100"]),
                    pv30 = row.longAt(index["PV30"]),
                    pv70 = row.longAt(index["PV70"]),
                    pv100 = row.longAt(index["PV100"]),
                    vol = row.longAt(index["VOL"]),
                    oi = row.longAt(index["OI"])
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Error) {
                throw err
            } catch (ex: Throwable) {
                throw Netflow2ClientError.UpstreamError("Failed to parse Netflow2 JSON row: $row", ex)
            }
        }
    }

    private fun extractDataset(root: JsonElement): JsonObject? {
        if (root is JsonObject) {
            if (root.hasDataAndColumns()) return root
            val explicitKeys = listOf("netflow2")
            for (key in explicitKeys) {
                val candidate = root[key] as? JsonObject
                if (candidate != null && candidate.hasDataAndColumns()) return candidate
            }

            return root.entries
                .asSequence()
                .filterNot { (key, _) -> key.endsWith(".cursor") }
                .sortedBy { it.key }
                .mapNotNull { (_, value) -> value as? JsonObject }
                .firstOrNull { it.hasDataAndColumns() }
        }

        return null
    }

    private fun JsonObject.hasDataAndColumns(): Boolean = containsKey("data") && containsKey("columns")

    private fun String.isHeaderLine(): Boolean {
        val tokens = split(';')
            .map { cleanCsvToken(it).uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        if (tokens.size < 2) return false
        val hasDate = tokens.any { it == "DATE" || it == "TRADEDATE" }
        val hasTicker = tokens.any { it == "SECID" || it == "TICKER" }
        return hasDate && hasTicker
    }

    private suspend fun <T> runResilient(url: String, block: suspend () -> T): HttpResult<T> {
        val deadline = clock.instant().plus(config.requestTimeout)
        var attempt = 0
        var lastError: Throwable? = null
        val maxAttempts = max(1, retryCfg.maxAttempts)

        while (attempt < maxAttempts) {
            try {
                val result = circuitBreaker.withPermit { HttpClients.measure(SERVICE) { block() } }
                return Result.success(result)
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Error) {
                throw err
            } catch (ex: Throwable) {
                lastError = ex
                val now = clock.instant()
                if (!shouldRetry(ex)) {
                    return Result.failure(wrapError(url, ex))
                }
                if (now.isAfter(deadline)) {
                    return Result.failure(Netflow2ClientError.TimeoutError(url, ex))
                }
                attempt += 1
                if (attempt >= maxAttempts) break
                metrics.retryCounter(SERVICE).increment()
                delay(computeBackoff(attempt))
            }
        }

        return Result.failure(wrapError(url, lastError))
    }

    private fun shouldRetry(error: Throwable): Boolean = when (error) {
        is Netflow2ClientError.ValidationError, is Netflow2ClientError.NotFound -> false
        is Netflow2ClientError.UpstreamError -> false
        is HttpClientError.ValidationError -> false
        is HttpClientError.DeserializationError -> false
        is HttpClientError.HttpStatusError -> {
            val code = error.status.value
            code == 429 || code >= 500 || retryCfg.retryOn.contains(code)
        }
        is HttpClientError.TimeoutError, is HttpClientError.NetworkError -> true
        else -> when (val mapped = error.toHttpClientError()) {
            is HttpClientError.TimeoutError, is HttpClientError.NetworkError -> true
            is HttpClientError.HttpStatusError -> {
                val code = mapped.status.value
                code == 429 || code >= 500 || retryCfg.retryOn.contains(code)
            }
            is HttpClientError.ValidationError, is HttpClientError.DeserializationError -> false
            else -> false
        }
    }

    private fun wrapError(url: String, cause: Throwable?): Netflow2ClientError {
        val root = cause ?: return Netflow2ClientError.UpstreamError("Netflow2 request failed for $url")
        return when (root) {
            is Netflow2ClientError -> root
            is HttpClientError -> wrapHttpClientError(url, root)
            is DateTimeParseException -> Netflow2ClientError.UpstreamError("Invalid date in Netflow2 payload", root)
            else -> {
                val httpError = root.toHttpClientError()
                wrapHttpClientError(url, httpError, root)
            }
        }
    }

    private fun wrapHttpClientError(
        url: String,
        error: HttpClientError,
        origin: Throwable? = error
    ): Netflow2ClientError = when (error) {
        is HttpClientError.HttpStatusError -> {
            val sec = extractSecFromUrl(error.requestUrl) ?: extractSecFromUrl(url)
            when {
                error.status == HttpStatusCode.NotFound && sec != null ->
                    Netflow2ClientError.NotFound(sec, error)
                error.status == HttpStatusCode.BadRequest && sec != null ->
                    Netflow2ClientError.ValidationError("invalid sec: $sec", error)
                else -> Netflow2ClientError.UpstreamError(
                    error.message ?: "HTTP error for $url",
                    error
                )
            }
        }
        is HttpClientError.TimeoutError -> Netflow2ClientError.TimeoutError(url, error)
        is HttpClientError.ValidationError -> Netflow2ClientError.ValidationError(
            requestFailedDetails(url, error.message),
            origin
        )
        else -> Netflow2ClientError.UpstreamError(requestFailedDetails(url, error.message), origin)
    }

    private fun requestFailedDetails(url: String, message: String?): String {
        val suffix = message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return "Netflow2 request failed for $url$suffix"
    }

    private fun extractSecFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val match = NETFLOW_SEC_REGEX.find(url) ?: return null
        val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return runCatchingNonFatal { normalizeTicker(raw) }.getOrNull()
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

    private fun List<String?>.longAt(index: Int?): Long? = index?.let { idx ->
        getOrNull(idx)?.takeIf { !it.isNullOrBlank() }?.let(::cleanCsvToken)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    }

    private fun cleanCsvToken(raw: String): String = raw.trim().removePrefix("\uFEFF").trim()

    private suspend fun readBodyOrNull(response: HttpResponse): String? = try {
        response.bodyAsText()
    } catch (ce: CancellationException) {
        throw ce
    } catch (err: Error) {
        throw err
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val SERVICE = "netflow2"
        private const val NETFLOW_PATH = "/iss/analyticalproducts/netflow2/securities/"
        private val NETFLOW_SEC_REGEX = Regex(
            "${Regex.escape(NETFLOW_PATH)}([^/?#]+)\\.(csv|json)",
            RegexOption.IGNORE_CASE
        )
    }
}

data class Netflow2ClientConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val format: Netflow2Format = Netflow2Format.CSV,
    val requestTimeout: Duration = Duration.ofSeconds(30)
) {
    companion object {
        private const val DEFAULT_BASE_URL = "https://iss.moex.com"
    }
}

enum class Netflow2Format { CSV, JSON }

sealed class Netflow2ClientError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    data class ValidationError(val details: String, val origin: Throwable? = null) :
        Netflow2ClientError(details, origin)

    data class NotFound(val sec: String, val origin: Throwable? = null) :
        Netflow2ClientError("sec not found: $sec", origin)

    data class UpstreamError(val details: String, val origin: Throwable? = null) :
        Netflow2ClientError(details, origin)

    data class TimeoutError(val url: String, val origin: Throwable? = null) :
        Netflow2ClientError("Netflow2 request timed out for $url", origin)
}
