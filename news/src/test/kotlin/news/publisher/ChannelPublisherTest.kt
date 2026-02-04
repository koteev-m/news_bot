package news.publisher

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
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
import news.publisher.store.InMemoryIdempotencyStore

class ChannelPublisherTest {
    private val config = NewsDefaults.defaultConfig.copy(
        channelId = -1001234567890L,
        botDeepLinkBase = "https://t.me/test_bot",
        maxPayloadBytes = 64
    )

    @Test
    fun `publish once respects idempotency`() = runTest {
        val bot = RecordingTelegramBot()
        bot.nextResponse = sendResponse(ok = true)
        val publisher = ChannelPublisher(bot, config, InMemoryIdempotencyStore())

        val first = publisher.publish("cluster-key", "text", InlineKeyboardMarkup())
        val second = publisher.publish("cluster-key", "text", InlineKeyboardMarkup())

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, bot.executed.size)
        val request = bot.executed.first() as SendMessage
        assertEquals(config.channelId, request.getParameters()["chat_id"])
    }

    @Test
    fun `failure response does not mark idempotency`() = runTest {
        val bot = RecordingTelegramBot()
        bot.nextResponse = sendResponse(ok = false, description = "rate limit")
        val publisher = ChannelPublisher(bot, config, InMemoryIdempotencyStore())

        val first = publisher.publish("cluster-key", "text", null)
        assertFalse(first)

        bot.nextResponse = sendResponse(ok = true)
        val second = publisher.publish("cluster-key", "text", null)
        assertTrue(second)
        assertEquals(2, bot.executed.size)
    }

    @Test
    fun `exceptions are captured and reported as failure`() = runTest {
        val bot = RecordingTelegramBot()
        bot.throwOnExecute = true
        val publisher = ChannelPublisher(bot, config, InMemoryIdempotencyStore())

        val result = publisher.publish("cluster-key", "text", null)
        assertFalse(result)
        assertEquals(0, bot.executed.size)
    }

    private class RecordingTelegramBot : TelegramBot("test-token") {
        val executed = mutableListOf<BaseRequest<*, *>>()
        var nextResponse: BaseResponse = sendResponse(ok = true)
        var throwOnExecute: Boolean = false

        override fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: BaseRequest<T, R>): R {
            if (throwOnExecute) {
                throw IllegalStateException("forced failure")
            }
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
