package cbr

import cache.TtlCache
import http.HttpClientError
import http.HttpMetricsRecorder
import http.HttpResult
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.micrometer.core.instrument.MeterRegistry
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.DocumentBuilderFactory
import java.io.IOException
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class CbrClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC()
) {
    private val metrics = HttpMetricsRecorder(meterRegistry, "cbr", javaClass.name)
    private val cache = TtlCache<String, List<CbrRate>>(clock)

    suspend fun getXmlDaily(date: LocalDate?): HttpResult<List<CbrRate>> {
        val cacheKey = date?.toString() ?: "latest"
        return runCatching {
            cache.getOrPut(cacheKey, DAILY_TTL) {
                metrics.record("xmlDaily") {
                    requestXmlDaily(date)
                }
            }
        }
    }

    private suspend fun requestXmlDaily(date: LocalDate?): List<CbrRate> {
        val response = httpClient.get("$baseUrl/scripts/XML_daily.asp") {
            accept(ContentType.Application.Xml)
            if (date != null) {
                parameter("date_req", date.format(CBR_REQUEST_FORMAT))
            }
        }.bodyAsText()
        return parseXml(response)
    }

    private fun parseXml(xml: String): List<CbrRate> {
        val builder = try {
            documentBuilderFactory.newDocumentBuilder()
        } catch (ex: ParserConfigurationException) {
            throw HttpClientError.UnexpectedError(null, ex)
        }
        val document = try {
            builder.parse(InputSource(StringReader(xml)))
        } catch (ex: SAXException) {
            throw HttpClientError.DeserializationError("Failed to parse CBR XML", ex)
        } catch (ex: IOException) {
            throw HttpClientError.DeserializationError("Failed to read CBR XML", ex)
        }
        val root = document.documentElement ?: throw HttpClientError.DeserializationError("Empty CBR XML payload")
        val asOfInstant = parseAsOf(root.getAttribute("Date"))
        val nodes = root.getElementsByTagName("Valute")
        val result = mutableListOf<CbrRate>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val code = element.textContentOf("CharCode")?.uppercase()
                ?: throw HttpClientError.DeserializationError("Missing CharCode in CBR payload")
            val nominalText = element.textContentOf("Nominal")
                ?: throw HttpClientError.DeserializationError("Missing Nominal for $code")
            val nominal = nominalText.toBigDecimalOrNull()
                ?: throw HttpClientError.DeserializationError("Invalid nominal '$nominalText' for $code")
            if (nominal.compareTo(BigDecimal.ZERO) == 0) {
                throw HttpClientError.DeserializationError("Nominal is zero for $code")
            }
            val valueText = element.textContentOf("Value")
                ?: throw HttpClientError.DeserializationError("Missing Value for $code")
            val value = valueText.replace(',', '.').toBigDecimalOrNull()
                ?: throw HttpClientError.DeserializationError("Invalid value '$valueText' for $code")
            val rate = value.divide(nominal, 8, RoundingMode.HALF_UP)
            result += CbrRate(code, rate, asOfInstant)
        }
        return result
    }

    private fun Element.textContentOf(tag: String): String? = getElementsByTagName(tag)
        .item(0)
        ?.textContent
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun parseAsOf(raw: String?): Instant {
        if (raw.isNullOrBlank()) {
            return clock.instant()
        }
        val date = try {
            LocalDate.parse(raw, CBR_RESPONSE_FORMAT)
        } catch (ex: DateTimeParseException) {
            throw HttpClientError.DeserializationError("Invalid CBR date '$raw'", ex)
        }
        return date.atStartOfDay(MOSCOW_ZONE).toInstant()
    }

    companion object {
        private val MOSCOW_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
        private val CBR_REQUEST_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val CBR_RESPONSE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val DAILY_TTL: Duration = 1.days
        private val documentBuilderFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
    }
}

data class CbrRate(
    val currencyCode: String,
    val rateRub: BigDecimal,
    val asOf: Instant
)
