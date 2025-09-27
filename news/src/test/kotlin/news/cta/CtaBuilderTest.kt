package news.cta

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CtaBuilderTest {
    private val base = "https://t.me/test_bot"

    @Test
    fun `deep link trims payload to limit`() {
        val payload = "A".repeat(128)
        val link = CtaBuilder.deepLink(base, payload, 64)
        val encoded = link.substringAfter("start=")
        val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8)
        assertEquals(64, decoded.length)
    }

    @Test
    fun `deep link has no spaces`() {
        val link = CtaBuilder.deepLink(base, "TICKER Test", 64)
        assertTrue(!link.contains(' '))
    }

    @Test
    fun `default markup contains expected buttons`() {
        val markup = CtaBuilder.defaultMarkup(base, 64, "SBER")
        val rows = markup.inlineKeyboard()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.size == 2 })
        val watchTicker = rows[0][0]
        assertEquals("Следить за SBER", watchTicker.text)
        assertTrue(watchTicker.url!!.contains("TICKER_SBER"))
        val btc = rows[0][1]
        assertEquals("BTC: +2% за час?", btc.text)
        assertTrue(btc.url!!.contains("TICKER_BTC_2PCT"))
        val weekly = rows[1][0]
        assertEquals("Еженед. напоминания", weekly.text)
        assertTrue(weekly.url!!.contains("TOPIC_WEEKLY"))
        val portfolio = rows[1][1]
        assertEquals("Мой портфель", portfolio.text)
        assertTrue(portfolio.url!!.contains("PORTFOLIO_HOME"))
    }

    @Test
    fun `default markup falls back without ticker`() {
        val markup = CtaBuilder.defaultMarkup(base, 64, null)
        val rows = markup.inlineKeyboard()
        assertEquals("Рынок сейчас", rows[0][0].text)
        assertTrue(rows[0][0].url!!.contains("TOPIC_MARKET"))
    }
}
