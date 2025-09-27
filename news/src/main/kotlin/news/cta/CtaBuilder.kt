package news.cta

import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object CtaBuilder {
    /** base: https://t.me/<bot_username>, payload ≤ maxBytes */
    fun deepLink(base: String, payload: String, maxBytes: Int): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val sanitizedBase = base.trim().trimEnd { it == '/' || it == '?' }
        val trimmedPayload = trimToBytes(payload, maxBytes)
        val encodedPayload = URLEncoder.encode(trimmedPayload, StandardCharsets.UTF_8)
        return "$sanitizedBase?start=$encodedPayload"
    }

    fun watchTicker(base: String, ticker: String, maxBytes: Int): InlineKeyboardButton {
        val normalizedTicker = normalizeTicker(ticker)
        val payload = "TICKER_${normalizedTicker.ifEmpty { "MARKET" }}"
        val link = deepLink(base, payload, maxBytes)
        return InlineKeyboardButton("Следить за ${displayTicker(ticker)}").url(link)
    }

    fun btcFast(base: String, maxBytes: Int): InlineKeyboardButton {
        val link = deepLink(base, "TICKER_BTC_2PCT", maxBytes)
        return InlineKeyboardButton("BTC: +2% за час?").url(link)
    }

    fun weeklyReminders(base: String, maxBytes: Int): InlineKeyboardButton {
        val link = deepLink(base, "TOPIC_WEEKLY", maxBytes)
        return InlineKeyboardButton("Еженед. напоминания").url(link)
    }

    fun myPortfolio(base: String, maxBytes: Int): InlineKeyboardButton {
        val link = deepLink(base, "PORTFOLIO_HOME", maxBytes)
        return InlineKeyboardButton("Мой портфель").url(link)
    }

    /** Стандартная раскладка 2×2 */
    fun defaultMarkup(base: String, maxBytes: Int, primaryTicker: String?): InlineKeyboardMarkup {
        val firstRowFirst = primaryTicker?.takeIf { it.isNotBlank() }
            ?.let { watchTicker(base, it, maxBytes) }
            ?: InlineKeyboardButton("Рынок сейчас").url(deepLink(base, "TOPIC_MARKET", maxBytes))
        val firstRowSecond = btcFast(base, maxBytes)
        val secondRowFirst = weeklyReminders(base, maxBytes)
        val secondRowSecond = myPortfolio(base, maxBytes)
        return InlineKeyboardMarkup(
            arrayOf(firstRowFirst, firstRowSecond),
            arrayOf(secondRowFirst, secondRowSecond)
        )
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
