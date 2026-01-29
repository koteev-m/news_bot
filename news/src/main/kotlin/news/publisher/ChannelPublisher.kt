package news.publisher

import analytics.AnalyticsPort
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import news.config.NewsConfig
import news.metrics.NewsMetricsPort
import news.metrics.NewsPublishResult
import news.metrics.NewsPublishType
import news.publisher.store.IdempotencyStore
import org.slf4j.LoggerFactory

open class ChannelPublisher(
    private val bot: TelegramBot,
    private val cfg: NewsConfig,
    private val store: IdempotencyStore,
    private val metrics: NewsMetricsPort = NewsMetricsPort.Noop,
    private val analytics: AnalyticsPort = AnalyticsPort.Noop,
) {
    private val logger = LoggerFactory.getLogger(ChannelPublisher::class.java)

    /** Идемпотентная публикация: вернёт false, если кластер уже публиковался */
    open suspend fun publish(clusterKey: String, text: String, markup: InlineKeyboardMarkup?): Boolean {
        val key = scopedKey(clusterKey)
        if (store.seen(key)) {
            logger.info("cluster {} already published", clusterKey)
            metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.SKIPPED)
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
                store.mark(key)
                metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.CREATED)
                analytics.track(
                    type = "post_published",
                    userId = null,
                    source = "news_publisher",
                    props = mapOf("cluster_key" to clusterKey),
                )
                true
            } else {
                logger.warn("telegram send failed for {}", clusterKey)
                metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.FAILED)
                false
            }
        } catch (ex: Exception) {
            logger.error("telegram send threw for {}", clusterKey, ex)
            metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.FAILED)
            false
        }
    }

    private fun scopedKey(clusterKey: String): String {
        return "${cfg.channelId}:$clusterKey"
    }
}
