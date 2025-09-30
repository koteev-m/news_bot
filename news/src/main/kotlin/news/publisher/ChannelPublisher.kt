package news.publisher

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import news.config.NewsConfig
import news.metrics.NewsMetricsPort
import news.publisher.store.IdempotencyStore
import org.slf4j.LoggerFactory

open class ChannelPublisher(
    private val bot: TelegramBot,
    private val cfg: NewsConfig,
    private val store: IdempotencyStore,
    private val metrics: NewsMetricsPort = NewsMetricsPort.Noop
) {
    private val logger = LoggerFactory.getLogger(ChannelPublisher::class.java)

    /** Идемпотентная публикация: вернёт false, если кластер уже публиковался */
    open suspend fun publish(clusterKey: String, text: String, markup: InlineKeyboardMarkup?): Boolean {
        if (store.seen(clusterKey)) {
            logger.info("cluster {} already published", clusterKey)
            return false
        }
        return try {
            val request = SendMessage(cfg.channelId, text)
                .parseMode(ParseMode.MarkdownV2)
            if (markup != null) {
                request.replyMarkup(markup)
            }
            val response = bot.execute(request)
            if (response.isOk) {
                store.mark(clusterKey)
                metrics.incPublish()
                true
            } else {
                logger.warn("telegram send failed for {}", clusterKey)
                false
            }
        } catch (ex: Exception) {
            logger.error("telegram send threw for {}", clusterKey, ex)
            false
        }
    }
}
