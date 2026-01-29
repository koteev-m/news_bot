package news.publisher

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import news.config.NewsConfig
import news.metrics.NewsMetricsPort
import news.metrics.NewsPublishResult
import news.metrics.NewsPublishType
import news.model.Cluster
import news.moderation.ModerationIds
import news.render.PostTemplates
import news.publisher.store.PostStatsStore
import org.slf4j.LoggerFactory

data class TelegramResult(val ok: Boolean, val description: String? = null, val messageId: Long? = null)

interface TelegramClient {
    fun send(request: SendMessage): TelegramResult
    fun edit(request: EditMessageText): TelegramResult
}

class PengradTelegramClient(private val bot: TelegramBot) : TelegramClient {
    override fun send(request: SendMessage): TelegramResult {
        val response = bot.execute(request)
        val messageId = response.message()?.messageId()?.toLong()
        return TelegramResult(response.isOk, response.description(), messageId)
    }

    override fun edit(request: EditMessageText): TelegramResult {
        val response = bot.execute(request)
        return TelegramResult(response.isOk, response.description())
    }
}

class TelegramPublisher(
    private val client: TelegramClient,
    private val config: NewsConfig,
    private val postStatsStore: PostStatsStore,
    private val idempotencyStore: IdempotencyStore,
    private val metrics: NewsMetricsPort = NewsMetricsPort.Noop
) {
    private val logger = LoggerFactory.getLogger(TelegramPublisher::class.java)

    suspend fun publishBreaking(cluster: Cluster, deepLink: String): PublishOutcome {
        val text = PostTemplates.renderBreaking(cluster, deepLink)
        val contentHash = PostHash.hash(text)
        val clusterId = ModerationIds.clusterIdFromKey(cluster.clusterKey)
        val existing = postStatsStore.findByCluster(config.channelId, clusterId)
        if (existing != null) {
            if (existing.contentHash == contentHash) {
                postStatsStore.recordDuplicate(config.channelId, clusterId)
                metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.SKIPPED)
                logger.info("Skipping cluster {} due to unchanged content", cluster.clusterKey)
                return PublishOutcome(PublishResult.SKIPPED, existing.messageId)
            }
            val editResult = editMessage(existing.messageId, text, deepLink, "cluster ${cluster.clusterKey}")
            return if (editResult.ok) {
                postStatsStore.recordEdit(config.channelId, clusterId, contentHash)
                metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.EDITED)
                metrics.incEdit()
                logger.info("Edited cluster {}", cluster.clusterKey)
                PublishOutcome(PublishResult.EDITED, existing.messageId)
            } else {
                metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.FAILED)
                logger.warn("Telegram edit failed for {} with error {}", cluster.clusterKey, editResult.description)
                PublishOutcome(PublishResult.FAILED, existing.messageId)
            }
        }
        val sendResult = sendMessage(text, deepLink, "cluster ${cluster.clusterKey}")
        if (sendResult.ok && sendResult.messageId != null) {
            postStatsStore.recordNew(config.channelId, clusterId, sendResult.messageId, contentHash)
            metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.CREATED)
            logger.info("Published cluster {}", cluster.clusterKey)
            return PublishOutcome(PublishResult.CREATED, sendResult.messageId)
        }
        metrics.incPublish(NewsPublishType.BREAKING, NewsPublishResult.FAILED)
        logger.warn("Telegram send failed for {} with error {}", cluster.clusterKey, sendResult.description)
        return PublishOutcome(PublishResult.FAILED, sendResult.messageId)
    }

    suspend fun publishDigest(clusters: List<Cluster>, deepLinkBuilder: (Cluster) -> String): PublishOutcome {
        if (clusters.isEmpty()) return PublishOutcome(PublishResult.SKIPPED, null)
        val digestKey = digestKey(clusters)
        val idempotencyKey = scopedKey(digestKey)
        if (idempotencyStore.seen(idempotencyKey)) {
            logger.info("Skipping digest {} due to idempotency", digestKey)
            metrics.incPublish(NewsPublishType.DIGEST, NewsPublishResult.SKIPPED)
            return PublishOutcome(PublishResult.SKIPPED, null)
        }
        val deepLink = deepLinkBuilder(clusters.first())
        val text = PostTemplates.renderDigest(clusters, deepLinkBuilder)
        val result = sendMessage(text, deepLink, "digest $digestKey")
        if (result.ok) {
            idempotencyStore.mark(idempotencyKey)
            metrics.incPublish(NewsPublishType.DIGEST, NewsPublishResult.CREATED)
            logger.info("Published digest {}", digestKey)
            return PublishOutcome(PublishResult.CREATED, result.messageId)
        }
        metrics.incPublish(NewsPublishType.DIGEST, NewsPublishResult.FAILED)
        logger.warn("Telegram send failed for digest {} with error {}", digestKey, result.description)
        return PublishOutcome(PublishResult.FAILED, result.messageId)
    }

    private fun sendMessage(text: String, deepLink: String, context: String): TelegramResult {
        return try {
            val request = SendMessage(config.channelId, text)
                .parseMode(ParseMode.MarkdownV2)
                .replyMarkup(InlineKeyboardMarkup(InlineKeyboardButton("Открыть в боте").url(deepLink)))
            client.send(request)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.error("Telegram send failed for {}", context, ex)
            TelegramResult(ok = false, description = ex.message)
        }
    }

    private fun editMessage(messageId: Long, text: String, deepLink: String, context: String): TelegramResult {
        return try {
            val request = EditMessageText(config.channelId, messageId.toInt(), text)
                .parseMode(ParseMode.MarkdownV2)
                .replyMarkup(InlineKeyboardMarkup(InlineKeyboardButton("Открыть в боте").url(deepLink)))
            client.edit(request)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.error("Telegram edit failed for {} messageId {}", context, messageId, ex)
            TelegramResult(ok = false, description = ex.message, messageId = messageId)
        }
    }

    private fun digestKey(clusters: List<Cluster>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val joined = clusters.map { it.clusterKey }
            .distinct()
            .sorted()
            .joinToString(separator = "|")
        val hash = digest.digest(joined.toByteArray(Charsets.UTF_8))
        return "digest:" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash.copyOf(12))
    }

    private fun scopedKey(key: String): String {
        return "${config.channelId}:$key"
    }
}
