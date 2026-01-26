package news.cta

import deeplink.DeepLinkPayload
import deeplink.DeepLinkType
import deeplink.InMemoryDeepLinkStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class CtaBuilderTest {
    private val base = "https://t.me/test_bot"
    private val ttl = 14.days

    @Test
    fun `deep link stores payload and returns short code`() {
        val store = InMemoryDeepLinkStore()
        val payload = DeepLinkPayload(type = DeepLinkType.TOPIC, id = "MARKET")
        val link = CtaBuilder.deepLink(base, payload, store, ttl)
        val code = startCode(link)
        assertTrue(code.length in 8..12)
        assertEquals(payload, store.get(code))
    }

    @Test
    fun `deep link appends ab marker within limit`() {
        val store = InMemoryDeepLinkStore()
        val payload = DeepLinkPayload(type = DeepLinkType.TOPIC, id = "NEWS")
        val link = CtaBuilder.deepLink(base, payload.copy(abVariant = "VARIANT_BETA"), store, ttl)
        val code = startCode(link)
        val stored = store.get(code)
        assertEquals("VARIANT_BETA", stored?.abVariant)
    }

    @Test
    fun `deep link has no spaces`() {
        val store = InMemoryDeepLinkStore()
        val link = CtaBuilder.deepLink(
            base,
            DeepLinkPayload(type = DeepLinkType.TICKER, id = "TICKER_TEST"),
            store,
            ttl
        )
        assertTrue(!link.contains(' '))
    }

    @Test
    fun `default markup contains expected buttons with ab`() {
        val store = InMemoryDeepLinkStore()
        val markup = CtaBuilder.defaultMarkup(base, store, ttl, "SBER", "B")
        val rows = markup.inlineKeyboard()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.size == 2 })
        val watchTicker = rows[0][0]
        assertEquals("Следить за SBER", watchTicker.text)
        val watchPayload = payloadFor(store, watchTicker.url!!)
        assertEquals(DeepLinkType.TICKER, watchPayload?.type)
        assertEquals("SBER", watchPayload?.id)
        assertEquals("B", watchPayload?.abVariant)
        val btc = rows[0][1]
        val btcPayload = payloadFor(store, btc.url!!)
        assertEquals("BTC: +2% за час?", btc.text)
        assertEquals("BTC_2PCT", btcPayload?.id)
        assertEquals("B", btcPayload?.abVariant)
        val weekly = rows[1][0]
        val weeklyPayload = payloadFor(store, weekly.url!!)
        assertEquals("Еженед. напоминания", weekly.text)
        assertEquals("WEEKLY", weeklyPayload?.id)
        assertEquals("B", weeklyPayload?.abVariant)
        val portfolio = rows[1][1]
        val portfolioPayload = payloadFor(store, portfolio.url!!)
        assertEquals("Мой портфель", portfolio.text)
        assertEquals(DeepLinkType.PORTFOLIO, portfolioPayload?.type)
        assertEquals("HOME", portfolioPayload?.id)
        assertEquals("B", portfolioPayload?.abVariant)
    }

    @Test
    fun `default markup falls back without ticker`() {
        val store = InMemoryDeepLinkStore()
        val markup = CtaBuilder.defaultMarkup(base, store, ttl, null, null)
        val rows = markup.inlineKeyboard()
        assertEquals("Рынок сейчас", rows[0][0].text)
        val payload = payloadFor(store, rows[0][0].url!!)
        assertEquals("MARKET", payload?.id)
    }

    private fun payloadFor(store: InMemoryDeepLinkStore, url: String): DeepLinkPayload? {
        return store.get(startCode(url))
    }

    private fun startCode(url: String): String {
        return url.substringAfter("start=")
    }
}
