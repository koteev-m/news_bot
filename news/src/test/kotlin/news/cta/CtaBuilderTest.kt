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
    fun `deep link appends ab marker within limit`() {
        val payload = "id=news|src=channel|cmp=${"x".repeat(20)}"
        val link = CtaBuilder.deepLink(base, payload, 64, "variant_beta")
        val encoded = link.substringAfter("start=")
        val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8)
        assertTrue(decoded.contains("|ab=VARIANT_BETA"))
        assertTrue(decoded.toByteArray(StandardCharsets.UTF_8).size <= 64)
    }

    @Test
    fun `deep link has no spaces`() {
        val link = CtaBuilder.deepLink(base, "TICKER Test", 64)
        assertTrue(!link.contains(' '))
    }

    @Test
    fun `default markup contains expected buttons with ab`() {
        val markup = CtaBuilder.defaultMarkup(base, 64, "SBER", "B")
        val rows = markup.inlineKeyboard()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.size == 2 })
        val watchTicker = rows[0][0]
        assertEquals("Следить за SBER", watchTicker.text)
        val watchUrl = watchTicker.url!!
        val watchPayload = startPayload(watchUrl)
        assertTrue(watchPayload.contains("TICKER_SBER"))
        assertTrue(watchPayload.contains("ab=B"))
        val btc = rows[0][1]
        val btcPayload = startPayload(btc.url!!)
        assertEquals("BTC: +2% за час?", btc.text)
        assertTrue(btcPayload.contains("TICKER_BTC_2PCT"))
        assertTrue(btcPayload.contains("ab=B"))
        val weekly = rows[1][0]
        val weeklyPayload = startPayload(weekly.url!!)
        assertEquals("Еженед. напоминания", weekly.text)
        assertTrue(weeklyPayload.contains("TOPIC_WEEKLY"))
        assertTrue(weeklyPayload.contains("ab=B"))
        val portfolio = rows[1][1]
        val portfolioPayload = startPayload(portfolio.url!!)
        assertEquals("Мой портфель", portfolio.text)
        assertTrue(portfolioPayload.contains("PORTFOLIO_HOME"))
        assertTrue(portfolioPayload.contains("ab=B"))
    }

    @Test
    fun `default markup falls back without ticker`() {
        val markup = CtaBuilder.defaultMarkup(base, 64, null, null)
        val rows = markup.inlineKeyboard()
        assertEquals("Рынок сейчас", rows[0][0].text)
        val payload = startPayload(rows[0][0].url!!)
        assertTrue(payload.contains("TOPIC_MARKET"))
    }

    private fun startPayload(url: String): String {
        val encoded = url.substringAfter("start=")
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8)
    }
}
