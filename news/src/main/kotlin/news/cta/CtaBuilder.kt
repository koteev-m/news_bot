package news.cta

import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import deeplink.DeepLinkPayload
import deeplink.DeepLinkStore
import deeplink.DeepLinkType
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.time.Duration

object CtaBuilder {
    /** base: https://t.me/<bot_username> */
    fun deepLink(base: String, payload: DeepLinkPayload, store: DeepLinkStore, ttl: Duration): String {
        val sanitizedBase = base.trim().trimEnd { it == '/' || it == '?' }
        val shortCode = store.put(payload, ttl)
        return "$sanitizedBase?start=$shortCode"
    }

    fun watchTicker(
        base: String,
        ticker: String,
        store: DeepLinkStore,
        ttl: Duration,
        abVariant: String? = null
    ): InlineKeyboardButton {
        val normalizedTicker = normalizeTicker(ticker)
        val payload = payloadWithAb(
            DeepLinkPayload(type = DeepLinkType.TICKER, id = normalizedTicker.ifEmpty { "MARKET" }),
            abVariant
        )
        val link = deepLink(base, payload, store, ttl)
        return InlineKeyboardButton("Следить за ${displayTicker(ticker)}").url(link)
    }

    fun btcFast(base: String, store: DeepLinkStore, ttl: Duration, abVariant: String? = null): InlineKeyboardButton {
        val payload = payloadWithAb(
            DeepLinkPayload(type = DeepLinkType.TICKER, id = "BTC_2PCT"),
            abVariant
        )
        val link = deepLink(base, payload, store, ttl)
        return InlineKeyboardButton("BTC: +2% за час?").url(link)
    }

    fun weeklyReminders(base: String, store: DeepLinkStore, ttl: Duration, abVariant: String? = null): InlineKeyboardButton {
        val payload = payloadWithAb(
            DeepLinkPayload(type = DeepLinkType.TOPIC, id = "WEEKLY"),
            abVariant
        )
        val link = deepLink(base, payload, store, ttl)
        return InlineKeyboardButton("Еженед. напоминания").url(link)
    }

    fun myPortfolio(base: String, store: DeepLinkStore, ttl: Duration, abVariant: String? = null): InlineKeyboardButton {
        val payload = payloadWithAb(DeepLinkPayload(type = DeepLinkType.PORTFOLIO, id = "HOME"), abVariant)
        val link = deepLink(base, payload, store, ttl)
        return InlineKeyboardButton("Мой портфель").url(link)
    }

    /** Стандартная раскладка 2×2 */
    fun defaultMarkup(
        base: String,
        store: DeepLinkStore,
        ttl: Duration,
        primaryTicker: String?,
        abVariant: String?
    ): InlineKeyboardMarkup {
        val firstRowFirst = primaryTicker?.takeIf { it.isNotBlank() }
            ?.let { watchTicker(base, it, store, ttl, abVariant) }
            ?: InlineKeyboardButton("Рынок сейчас").url(
                deepLink(
                    base,
                    payloadWithAb(DeepLinkPayload(type = DeepLinkType.TOPIC, id = "MARKET"), abVariant),
                    store,
                    ttl
                )
            )
        val firstRowSecond = btcFast(base, store, ttl, abVariant)
        val secondRowFirst = weeklyReminders(base, store, ttl, abVariant)
        val secondRowSecond = myPortfolio(base, store, ttl, abVariant)
        return InlineKeyboardMarkup(
            arrayOf(firstRowFirst, firstRowSecond),
            arrayOf(secondRowFirst, secondRowSecond)
        )
    }

    private fun payloadWithAb(payload: DeepLinkPayload, abVariant: String?): DeepLinkPayload {
        val sanitized = abVariant?.let { sanitizeVariant(it) } ?: return payload
        if (sanitized.isEmpty()) {
            return payload
        }
        return payload.copy(abVariant = sanitized)
    }

    private fun sanitizeVariant(value: String): String {
        val trimmed = value.trim().take(16)
        val filtered = trimmed.filter { it.isLetterOrDigit() || it == '_' }
        return filtered.uppercase(Locale.US)
    }

    private fun normalizeTicker(ticker: String): String {
        val upper = ticker.trim().uppercase(Locale.US)
        val filtered = upper.filter { it.isLetterOrDigit() || it == '_' }
        return trimToBytes(filtered.ifEmpty { "MARKET" }, 48)
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

    private fun displayTicker(ticker: String): String {
        val trimmed = ticker.trim()
        return if (trimmed.isEmpty()) "рынком" else trimmed.uppercase(Locale.getDefault())
    }
}
