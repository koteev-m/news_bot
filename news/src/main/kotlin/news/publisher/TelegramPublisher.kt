package news.publisher

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import java.security.MessageDigest
import java.util.Base64
import news.config.NewsConfig
import news.model.Cluster
import news.render.PostTemplates
import org.slf4j.LoggerFactory

data class TelegramResult(val ok: Boolean, val description: String? = null)

interface TelegramClient {
    fun send(request: SendMessage): TelegramResult
}

class PengradTelegramClient(private val bot: TelegramBot) : TelegramClient {
    override fun send(request: SendMessage): TelegramResult {
        val response = bot.execute(request)
        return TelegramResult(response.isOk, response.description())
    }
}

class TelegramPublisher(
    private val client: TelegramClient,
    private val config: NewsConfig,
    private val idempotencyStore: IdempotencyStore
) {
    private val logger = LoggerFactory.getLogger(TelegramPublisher::class.java)

    suspend fun publishBreaking(cluster: Cluster, deepLink: String): Boolean {
        if (idempotencyStore.seen(cluster.clusterKey)) {
            logger.info("Skipping cluster {} due to idempotency", cluster.clusterKey)
            return false
        }
        return sendMessage(cluster.clusterKey, PostTemplates.renderBreaking(cluster, deepLink), deepLink)
    }

    suspend fun publishDigest(clusters: List<Cluster>, deepLinkBuilder: (Cluster) -> String): Boolean {
        if (clusters.isEmpty()) return false
        val digestKey = digestKey(clusters)
        if (idempotencyStore.seen(digestKey)) {
            logger.info("Skipping digest {} due to idempotency", digestKey)
            return false
        }
        val deepLink = deepLinkBuilder(clusters.first())
        val text = PostTemplates.renderDigest(clusters, deepLinkBuilder)
        return sendMessage(digestKey, text, deepLink)
    }

    private fun sendMessage(key: String, text: String, deepLink: String): Boolean {
        return try {
            val request = SendMessage(config.channelId, text)
                .parseMode(ParseMode.MarkdownV2)
                .replyMarkup(InlineKeyboardMarkup(InlineKeyboardButton("Открыть в боте").url(deepLink)))
            val response = client.send(request)
            if (response.ok) {
                idempotencyStore.mark(key)
                logger.info("Published cluster {}", key)
                true
            } else {
                logger.warn("Telegram send failed for {} with error {}", key, response.description)
                false
            }
        } catch (ex: Exception) {
            logger.error("Telegram send threw for {}", key, ex)
            false
        }
    }

    private fun digestKey(clusters: List<Cluster>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val joined = clusters.joinToString(separator = "|") { it.clusterKey }
        val hash = digest.digest(joined.toByteArray(Charsets.UTF_8))
        return "digest:" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash.copyOf(12))
    }
}
