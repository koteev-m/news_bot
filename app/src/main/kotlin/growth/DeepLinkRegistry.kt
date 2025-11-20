package growth

import io.ktor.http.Parameters
import java.util.Base64

private const val MAX_START_PAYLOAD_BYTES = 64
private const val MAX_STARTAPP_PAYLOAD_BYTES = 512
private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+={0,2}")

sealed class DeepLinkPayload(open val raw: String) {
    data class Start(val decoded: String, override val raw: String) : DeepLinkPayload(raw)
    data class StartApp(val decoded: String, override val raw: String) : DeepLinkPayload(raw)
}

class DeepLinkRegistry(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun parse(parameters: Parameters): DeepLinkPayload? {
        val start = parameters["start"]?.takeIf { it.isNotBlank() }
        val startApp = parameters["startapp"]?.takeIf { it.isNotBlank() }
        if (start != null && startApp != null) return null
        return when {
            start != null -> parseStart(start)
            startApp != null -> parseStartApp(startApp)
            else -> null
        }
    }

    private fun parseStart(raw: String): DeepLinkPayload.Start? {
        if (!raw.matches(BASE64URL_REGEX)) return null
        if (raw.toByteArray().size > MAX_START_PAYLOAD_BYTES) return null
        val decoded = decodeBase64Url(raw) ?: return null
        return DeepLinkPayload.Start(decoded = decoded, raw = raw)
    }

    private fun parseStartApp(raw: String): DeepLinkPayload.StartApp? {
        if (!raw.matches(BASE64URL_REGEX)) return null
        if (raw.toByteArray().size > MAX_STARTAPP_PAYLOAD_BYTES) return null
        val decoded = decodeBase64Url(raw) ?: return null
        return DeepLinkPayload.StartApp(decoded = decoded, raw = raw)
    }

    private fun decodeBase64Url(value: String): String? = runCatching {
        val decoder = Base64.getUrlDecoder()
        val bytes = decoder.decode(value)
        String(bytes, Charsets.UTF_8)
    }.getOrNull()
}
