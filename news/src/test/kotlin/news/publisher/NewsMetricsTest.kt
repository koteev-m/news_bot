package news.publisher

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import news.config.NewsDefaults
import news.metrics.NewsMetricsPort
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
        assertEquals(1, metrics.publishes)
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
        assertEquals(1, metrics.publishes)
        assertEquals(1, bot.executed.size)
    }

    private class RecordingMetrics : NewsMetricsPort {
        var publishes: Int = 0

        override fun incPublish() {
            publishes += 1
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
