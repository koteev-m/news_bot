package news.dedup

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import news.config.NewsDefaults
import news.model.Article

class ClustererTest {
    private val config = NewsDefaults.defaultConfig

    @Test
    fun `similar articles form single cluster`() {
        val clusterer = Clusterer(config)
        val baseTime = Instant.parse("2025-01-20T07:00:00Z")
        val articles = listOf(
            Article(
                id = "cbr-1",
                url = "https://www.cbr.ru/press/PR/?file=20012025_100000keyrate.htm",
                domain = "www.cbr.ru",
                title = "Банк России снижает ключевую ставку",
                summary = "Банк России сообщил о снижении ставки, затрагивая SBER и GAZP.",
                publishedAt = baseTime,
                language = "ru",
                tickers = setOf("SBER", "GAZP"),
                entities = setOf("Bank of Russia", "SBER", "GAZP")
            ),
            Article(
                id = "moex-1",
                url = "https://www.moex.com/n46700",
                domain = "www.moex.com",
                title = "Мосбиржа сообщила о ставке Банка России",
                summary = "Мосбиржа подтверждает действия регулятора и упоминает SBER.",
                publishedAt = baseTime.plusSeconds(120),
                language = "ru",
                tickers = setOf("SBER"),
                entities = setOf("Moscow Exchange", "SBER")
            )
        )

        val clusters = clusterer.cluster(articles)

        assertEquals(1, clusters.size)
        val cluster = clusters.first()
        assertEquals("www.cbr.ru", cluster.canonical.domain)
        assertEquals(2, cluster.articles.size)
        assertTrue("SBER" in cluster.topics)
    }
}
