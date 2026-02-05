package deeplink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class DeepLinkPayload(
    val v: Int = 1,
    val type: DeepLinkType,
    val id: String? = null,
    val abVariant: String? = null,
    val utmSource: String? = null,
    val utmMedium: String? = null,
    val utmCampaign: String? = null,
    val ref: String? = null,
) {
    fun canonicalLabel(): String =
        when (type) {
            DeepLinkType.TICKER -> canonicalToken(prefix = "TICKER", value = id) ?: "TICKER_UNKNOWN"
            DeepLinkType.TOPIC -> canonicalToken(prefix = "TOPIC", value = id) ?: "TOPIC_UNKNOWN"
            DeepLinkType.PORTFOLIO -> "PORTFOLIO"
        }

    private fun canonicalToken(
        prefix: String,
        value: String?,
    ): String? {
        val sanitized = sanitizeToken(value) ?: return null
        return "${prefix}_${sanitized.uppercase(Locale.ROOT)}"
    }

    private fun sanitizeToken(value: String?): String? {
        if (value.isNullOrEmpty()) {
            return null
        }
        if (value.length > TOKEN_MAX_LEN) {
            return null
        }
        if (value.any { !it.isAllowedTokenChar() }) {
            return null
        }
        return value
    }

    private fun Char.isAllowedTokenChar(): Boolean =
        this in 'a'..'z' ||
            this in 'A'..'Z' ||
            this in '0'..'9' ||
            this == '.' ||
            this == '_' ||
            this == '-'
}

private const val TOKEN_MAX_LEN = 32

@Serializable
enum class DeepLinkType {
    @SerialName("ticker")
    TICKER,

    @SerialName("topic")
    TOPIC,

    @SerialName("portfolio")
    PORTFOLIO,
}
