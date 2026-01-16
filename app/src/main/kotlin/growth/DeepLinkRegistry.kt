package growth

import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.util.Base64
import common.runCatchingNonFatal

/**
 * Реестр deeplink-пейлоадов (?start / ?startapp) с безопасной валидацией.
 * Лимиты подтягиваются из конфига (символы, не байты).
 * Правила:
 *  - start:   ^[A-Za-z0-9_-]{1,limitStart}$
 *  - startapp:^[A-Za-z0-9_-]{1,limitStartApp}$
 * Затем обязательная попытка base64url-decode (без паддинга). При неудаче -> null.
 * Логи не содержат значения payload, только длину/валидность.
 */
class DeepLinkRegistry(
    private val limitStart: Int,
    private val limitStartApp: Int,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val log = LoggerFactory.getLogger(DeepLinkRegistry::class.java)

    sealed class Parsed(open val raw: String) {
        data class Start(
            val decoded: String,
            val canonicalId: String,
            override val raw: String,
        ) : Parsed(raw)

        data class StartApp(
            val decoded: String,
            val canonicalId: String,
            override val raw: String,
        ) : Parsed(raw)
    }

    /** DTO каталога v1 (минимально по PRD). */
    @Serializable
    private data class StartV1(
        val v: Int,
        val t: String,
        val s: String? = null,
        val b: String? = null,
        val i: String? = null,
        val h: String? = null,
        val a: String? = null,
    )

    fun parse(parameters: Parameters): Parsed? {
        val starts = parameters.getAll("start")?.filterNot { it.isBlank() }.orEmpty()
        val startApps = parameters.getAll("startapp")?.filterNot { it.isBlank() }.orEmpty()
        if (starts.isNotEmpty() && startApps.isNotEmpty()) {
            log.debug("deeplink: mixed params rejected (start+startapp)")
            return null
        }
        return when {
            starts.size == 1 -> parseStart(starts.first())
            startApps.size == 1 -> parseStartApp(startApps.first())
            else -> null
        }
    }

    fun parseStart(raw: String): Parsed.Start? {
        val re = Regex("^[A-Za-z0-9_-]{1,$limitStart}$")
        if (!re.matches(raw)) {
            log.debug("deeplink:start invalid pattern len={}", raw.length)
            return null
        }
        val decoded = decodeBase64Url(raw) ?: run {
            log.debug("deeplink:start b64url decode failed len={}", raw.length)
            return null
        }
        val canonical = canonicalIdForStart(decoded)
        return Parsed.Start(decoded = decoded, canonicalId = canonical, raw = raw)
    }

    fun parseStartApp(raw: String): Parsed.StartApp? {
        val re = Regex("^[A-Za-z0-9_-]{1,$limitStartApp}$")
        if (!re.matches(raw)) {
            log.debug("deeplink:startapp invalid pattern len={}", raw.length)
            return null
        }
        val decoded = decodeBase64Url(raw) ?: run {
            log.debug("deeplink:startapp b64url decode failed len={}", raw.length)
            return null
        }
        val canonical = canonicalIdForStart(decoded)
        return Parsed.StartApp(decoded = decoded, canonicalId = canonical, raw = raw)
    }

    private fun decodeBase64Url(value: String): String? = runCatchingNonFatal {
        val bytes = Base64.getUrlDecoder().decode(value)
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    /** Канонический ID (для метрик/каталога), без утечки сырых данных. */
    private fun canonicalIdForStart(decodedJson: String): String = runCatchingNonFatal {
        val elem = json.parseToJsonElement(decodedJson)
        val v = elem.jsonObject["v"]?.jsonPrimitive?.intOrNull ?: 1
        if (v != 1) return@runCatchingNonFatal "UNKNOWN_V$v"
        val payload = json.decodeFromJsonElement<StartV1>(elem)
        when (payload.t) {
            "w" -> payload.s?.let { "TICKER_${it.uppercase()}" } ?: "TICKER_UNKNOWN"
            "topic" -> payload.i?.let { "TOPIC_${it.uppercase()}" } ?: "TOPIC_UNKNOWN"
            "p" -> "PORTFOLIO"
            else -> "UNKNOWN"
        }
    }.getOrElse { "UNKNOWN" }
}
