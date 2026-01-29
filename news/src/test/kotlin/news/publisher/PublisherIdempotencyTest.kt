package news.publisher

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import news.config.NewsDefaults
import news.model.Article
import news.model.Cluster
import news.publisher.store.PublishedPost
import news.publisher.store.PostStatsStore
import java.util.UUID

class PublisherIdempotencyTest {
    private val config = NewsDefaults.defaultConfig.copy(channelId = -100L)

    @Test
    fun `duplicate cluster is not republished`() = runTest {
        val store = InMemoryIdempotencyStore()
        val postStatsStore = InMemoryPostStatsStore()
        val client = CountingTelegramClient()
        val publisher = TelegramPublisher(client, config, postStatsStore, store)
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

        assertEquals(PublishResult.CREATED, first.result)
        assertEquals(PublishResult.SKIPPED, second.result)
        assertEquals(1, client.sentMessages)
    }

    private class CountingTelegramClient : TelegramClient {
        var sentMessages: Int = 0
        override fun send(request: com.pengrad.telegrambot.request.SendMessage): TelegramResult {
            sentMessages += 1
            return TelegramResult(ok = true, messageId = 123L)
        }

        override fun edit(request: com.pengrad.telegrambot.request.EditMessageText): TelegramResult {
            return TelegramResult(ok = true, messageId = 123L)
        }
    }

    private class InMemoryPostStatsStore : PostStatsStore {
        private val entries = mutableMapOf<UUID, PublishedPost>()

        override suspend fun findByCluster(channelId: Long, clusterId: UUID): PublishedPost? {
            return entries[clusterId]
        }

        override suspend fun recordNew(channelId: Long, clusterId: UUID, messageId: Long, contentHash: String) {
            entries[clusterId] = PublishedPost(messageId, contentHash, 0)
        }

        override suspend fun recordEdit(channelId: Long, clusterId: UUID, contentHash: String) {
            val existing = entries[clusterId] ?: return
            entries[clusterId] = existing.copy(contentHash = contentHash, duplicateCount = existing.duplicateCount + 1)
        }

        override suspend fun recordDuplicate(channelId: Long, clusterId: UUID) {
            val existing = entries[clusterId] ?: return
            entries[clusterId] = existing.copy(duplicateCount = existing.duplicateCount + 1)
        }
    }
}
