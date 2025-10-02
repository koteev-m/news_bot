package news.cta

import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object CtaBuilder {
    /** base: https://t.me/<bot_username>, payload ≤ maxBytes */
    fun deepLink(base: String, payload: String, maxBytes: Int, abVariant: String? = null): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val sanitizedBase = base.trim().trimEnd { it == '/' || it == '?' }
        val payloadWithAb = appendAbMarker(payload, abVariant)
        val trimmedPayload = trimToBytes(payloadWithAb, maxBytes)
        val encodedPayload = URLEncoder.encode(trimmedPayload, StandardCharsets.UTF_8)
        return "$sanitizedBase?start=$encodedPayload"
    }

    fun watchTicker(base: String, ticker: String, maxBytes: Int, abVariant: String? = null): InlineKeyboardButton {
        val normalizedTicker = normalizeTicker(ticker)
        val payload = "TICKER_${normalizedTicker.ifEmpty { "MARKET" }}"
        val link = deepLink(base, payload, maxBytes, abVariant)
        return InlineKeyboardButton("Следить за ${displayTicker(ticker)}").url(link)
    }

    fun btcFast(base: String, maxBytes: Int, abVariant: String? = null): InlineKeyboardButton {
        val link = deepLink(base, "TICKER_BTC_2PCT", maxBytes, abVariant)
        return InlineKeyboardButton("BTC: +2% за час?").url(link)
    }

    fun weeklyReminders(base: String, maxBytes: Int, abVariant: String? = null): InlineKeyboardButton {
        val link = deepLink(base, "TOPIC_WEEKLY", maxBytes, abVariant)
        return InlineKeyboardButton("Еженед. напоминания").url(link)
    }

    fun myPortfolio(base: String, maxBytes: Int, abVariant: String? = null): InlineKeyboardButton {
        val link = deepLink(base, "PORTFOLIO_HOME", maxBytes, abVariant)
        return InlineKeyboardButton("Мой портфель").url(link)
    }

    /** Стандартная раскладка 2×2 */
    fun defaultMarkup(base: String, maxBytes: Int, primaryTicker: String?, abVariant: String?): InlineKeyboardMarkup {
        val firstRowFirst = primaryTicker?.takeIf { it.isNotBlank() }
            ?.let { watchTicker(base, it, maxBytes, abVariant) }
            ?: InlineKeyboardButton("Рынок сейчас").url(deepLink(base, "TOPIC_MARKET", maxBytes, abVariant))
        val firstRowSecond = btcFast(base, maxBytes, abVariant)
        val secondRowFirst = weeklyReminders(base, maxBytes, abVariant)
        val secondRowSecond = myPortfolio(base, maxBytes, abVariant)
        return InlineKeyboardMarkup(
            arrayOf(firstRowFirst, firstRowSecond),
            arrayOf(secondRowFirst, secondRowSecond)
        )
    }

    private fun appendAbMarker(payload: String, abVariant: String?): String {
        val sanitized = abVariant?.let { sanitizeVariant(it) } ?: return payload
        if (sanitized.isEmpty()) {
            return payload
        }
        return if (payload.isEmpty()) "ab=$sanitized" else "$payload|ab=$sanitized"
    }

    private fun sanitizeVariant(value: String): String {
        val trimmed = value.trim().take(16)
        val filtered = trimmed.filter { it.isLetterOrDigit() || it == '_' }
        return filtered.uppercase(Locale.US)
    }

    private fun trimToBytes(value: String, maxBytes: Int): String {
        var currentBytes = 0
        val builder = StringBuilder()
        for (char in value) {
            val charBytes = char.toString().toByteArray(StandardCharsets.UTF_8).size
            if (currentBytes + charBytes > maxBytes) {
                break
            }
            builder.append(char)
            currentBytes += charBytes
        }
        return builder.toString()
    }

    private fun normalizeTicker(ticker: String): String {
        val upper = ticker.trim().uppercase(Locale.US)
        val filtered = upper.filter { it.isLetterOrDigit() || it == '_' }
        return trimToBytes(filtered.ifEmpty { "MARKET" }, 48)
    }

    private fun displayTicker(ticker: String): String {
        val trimmed = ticker.trim()
        return if (trimmed.isEmpty()) "рынком" else trimmed.uppercase(Locale.getDefault())
    }
}
