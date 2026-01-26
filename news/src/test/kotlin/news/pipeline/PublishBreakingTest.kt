package news.pipeline

import ab.Assignment
import ab.ExperimentsService
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import deeplink.DeepLinkPayload
import deeplink.InMemoryDeepLinkStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.days
import news.config.NewsConfig
import news.config.NewsDefaults
import news.model.Article
import news.model.Cluster
import news.publisher.ChannelPublisher
import news.publisher.store.InMemoryIdempotencyStore

class PublishBreakingTest {
    private val config = NewsDefaults.defaultConfig.copy(
        channelId = -1001234567890L,
        botDeepLinkBase = "https://t.me/test_bot",
        maxPayloadBytes = 64
    )
    private val ttl = 14.days

    @Test
    fun `publish composes markdown and markup`() = runTest {
        val store = InMemoryDeepLinkStore()
        val publisher = RecordingPublisher(config)
        val useCase = PublishBreaking(config, publisher, store, ttl)
        val article = Article(
            id = "id-1",
            url = "https://example.com/news",
            domain = "example.com",
            title = "Сбербанк (MOEX:SBER)!",
            summary = "Рост +2% #финансы",
            publishedAt = Instant.parse("2024-06-01T10:15:30Z"),
            language = "ru",
            tickers = setOf("SBER"),
            entities = setOf("Sberbank")
        )
        val cluster = Cluster(
            clusterKey = "cluster-key-1",
            canonical = article,
            articles = listOf(article),
            topics = setOf("Finance"),
            createdAt = article.publishedAt
        )

        val result = useCase.publish(cluster, "SBER")

        assertTrue(result)
        assertEquals(listOf(cluster.clusterKey), publisher.publishedKeys)
        val text = publisher.lastText
        assertNotNull(text)
        assertTrue(text.contains("\\(MOEX:SBER\\)"))
        assertTrue(text.contains("Рост \\+2%"))
        assertTrue(text.contains("[Открыть в боте]"))
        val markup = publisher.lastMarkup
        assertNotNull(markup)
        val rows = markup.inlineKeyboard()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.size == 2 })
        assertTrue(rows.flatten().all { it.url!!.startsWith("https://t.me/test_bot?start=") })
        val payloads = rows.flatten().mapNotNull { payloadFor(store, it.url!!) }
        assertEquals(4, payloads.size)
    }

    @Test
    fun `publish attaches ab variant when experiments available`() = runTest {
        val store = InMemoryDeepLinkStore()
        val publisher = RecordingPublisher(config)
        val experiments = FakeExperimentsService()
        val useCase = PublishBreaking(config, publisher, store, ttl, experiments)
        val article = Article(
            id = "id-1",
            url = "https://example.com/news",
            domain = "example.com",
            title = "BTC spikes",
            summary = "+5%",
            publishedAt = Instant.parse("2024-06-02T00:00:00Z"),
            language = "en",
            tickers = setOf("BTC"),
            entities = emptySet()
        )
        val cluster = Cluster(
            clusterKey = "cluster-key-2",
            canonical = article,
            articles = listOf(article),
            topics = setOf("Crypto"),
            createdAt = article.publishedAt
        )

        val result = useCase.publish(cluster, "BTC")
        assertTrue(result)
        assertEquals(listOf(cluster.clusterKey), publisher.publishedKeys)
        val markup = publisher.lastMarkup
        assertNotNull(markup)
        val allUrls = markup.inlineKeyboard().flatten().mapNotNull { it.url }
        val payloads = allUrls.mapNotNull { payloadFor(store, it) }
        assertTrue(payloads.all { it.abVariant == "TEST" })
        assertTrue(experiments.assignedUsers.contains(config.channelId))
    }

    private class RecordingPublisher(config: NewsConfig) : ChannelPublisher(
        NoopTelegramBot(),
        config,
        InMemoryIdempotencyStore()
    ) {
        val publishedKeys = mutableListOf<String>()
        var lastText: String? = null
        var lastMarkup: InlineKeyboardMarkup? = null

        override suspend fun publish(clusterKey: String, text: String, markup: InlineKeyboardMarkup?): Boolean {
            publishedKeys += clusterKey
            lastText = text
            lastMarkup = markup
            return true
        }
    }

    private class NoopTelegramBot : TelegramBot("test-token") {
        override fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: BaseRequest<T, R>): R {
            throw UnsupportedOperationException("not used in tests")
        }
    }

    private class FakeExperimentsService : ExperimentsService {
        val assignedUsers = mutableListOf<Long>()

        override suspend fun assign(userId: Long, key: String): Assignment {
            assignedUsers += userId
            return Assignment(userId = userId, key = key, variant = "TEST")
        }

        override suspend fun activeAssignments(userId: Long): List<Assignment> = listOf(assign(userId, "cta_copy"))
    }
    private fun payloadFor(store: InMemoryDeepLinkStore, url: String): DeepLinkPayload? {
        val code = url.substringAfter("start=")
        return store.get(code)
    }
}
