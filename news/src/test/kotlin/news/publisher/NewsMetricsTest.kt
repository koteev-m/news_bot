package news.publisher

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import news.config.NewsDefaults
import news.metrics.NewsMetricsPort
import news.metrics.NewsPublishResult
import news.metrics.NewsPublishType
import news.publisher.store.InMemoryIdempotencyStore

class NewsMetricsTest {
    private val config = NewsDefaults.defaultConfig.copy(
        channelId = -1001234567890L,
        botDeepLinkBase = "https://t.me/test_bot",
        maxPayloadBytes = 64
    )

    @Test
    fun `successful publish increments metric`() = runTest {
        val bot = RecordingTelegramBot()
        val metrics = RecordingMetrics()
        bot.nextResponse = sendResponse(ok = true)
        val publisher = ChannelPublisher(bot, config, InMemoryIdempotencyStore(), metrics)

        val result = publisher.publish("cluster-key", "text", null)

        assertTrue(result)
        assertEquals(1, metrics.count(NewsPublishType.BREAKING, NewsPublishResult.CREATED))
        assertEquals(1, bot.executed.size)
    }

    @Test
    fun `idempotent repeat does not increment metric`() = runTest {
        val bot = RecordingTelegramBot()
        val metrics = RecordingMetrics()
        bot.nextResponse = sendResponse(ok = true)
        val publisher = ChannelPublisher(bot, config, InMemoryIdempotencyStore(), metrics)

        val first = publisher.publish("cluster-key", "text", null)
        val second = publisher.publish("cluster-key", "text", null)

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, metrics.count(NewsPublishType.BREAKING, NewsPublishResult.CREATED))
        assertEquals(1, metrics.count(NewsPublishType.BREAKING, NewsPublishResult.SKIPPED))
        assertEquals(1, bot.executed.size)
    }

    private class RecordingMetrics : NewsMetricsPort {
        private val counts = mutableMapOf<Pair<NewsPublishType, NewsPublishResult>, Int>()

        override fun incPublish(type: NewsPublishType, result: NewsPublishResult) {
            val key = type to result
            counts[key] = (counts[key] ?: 0) + 1
        }

        override fun incEdit() {
        }

        override fun incCandidatesReceived(sourceId: String, count: Int) {
        }

        override fun incClustersCreated(eventType: news.model.EventType) {
        }

        override fun incRouted(route: news.routing.EventRoute) {
        }

        override fun incDropped(reason: news.routing.DropReason) {
        }

        override fun incPublishJobStatus(status: news.pipeline.PublishJobStatus, count: Int) {
        }

        override fun incModerationQueueStatus(status: news.moderation.ModerationStatus, count: Int) {
        }

        override fun setDedupRatio(ratio: Double) {
        }

        fun count(type: NewsPublishType, result: NewsPublishResult): Int {
            return counts[type to result] ?: 0
        }
    }

    private class RecordingTelegramBot : TelegramBot("test-token") {
        val executed = mutableListOf<BaseRequest<*, *>>()
        var nextResponse: BaseResponse = sendResponse(ok = true)

        override fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: BaseRequest<T, R>): R {
            executed += request
            @Suppress("UNCHECKED_CAST")
            return nextResponse as R
        }
    }
}

private fun sendResponse(ok: Boolean, description: String? = null): SendResponse {
    val ctor = SendResponse::class.java.getDeclaredConstructor()
    ctor.isAccessible = true
    val response = ctor.newInstance()
    setField(response, "ok", ok)
    if (description != null) {
        setField(response, "description", description)
    }
    return response
}

private fun setField(response: BaseResponse, name: String, value: Any) {
    val field = BaseResponse::class.java.getDeclaredField(name)
    field.isAccessible = true
    if (value is Boolean) {
        field.setBoolean(response, value)
    } else {
        field.set(response, value)
    }
}
