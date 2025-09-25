package news.publisher

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import news.config.NewsDefaults
import news.model.Article
import news.model.Cluster

class PublisherIdempotencyTest {
    private val config = NewsDefaults.defaultConfig.copy(channelId = -100L)

    @Test
    fun `duplicate cluster is not republished`() = runTest {
        val store = InMemoryIdempotencyStore()
        val client = CountingTelegramClient()
        val publisher = TelegramPublisher(client, config, store)
        val article = Article(
            id = "cluster-1",
            url = "https://www.cbr.ru/news/1",
            domain = "www.cbr.ru",
            title = "Банк России объявил решение",
            summary = "",
            publishedAt = Instant.parse("2025-01-20T07:00:00Z"),
            language = "ru",
            tickers = emptySet(),
            entities = setOf("Bank of Russia")
        )
        val cluster = Cluster(
            clusterKey = "cluster-key",
            canonical = article,
            articles = listOf(article),
            topics = setOf("Bank of Russia"),
            createdAt = article.publishedAt
        )

        val first = publisher.publishBreaking(cluster, "https://t.me/test_bot?start=payload")
        val second = publisher.publishBreaking(cluster, "https://t.me/test_bot?start=payload")

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, client.sentMessages)
    }

    private class CountingTelegramClient : TelegramClient {
        var sentMessages: Int = 0
        override fun send(request: com.pengrad.telegrambot.request.SendMessage): TelegramResult {
            sentMessages += 1
            return TelegramResult(ok = true)
        }
    }
}
