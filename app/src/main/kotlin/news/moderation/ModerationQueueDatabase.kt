package news.moderation

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import news.render.ModerationTemplates

class ModerationQueueDatabase(
    private val repository: ModerationRepository,
    private val bot: TelegramBot,
    private val config: ModerationBotConfig,
) : ModerationQueue {
    private val logger = LoggerFactory.getLogger(ModerationQueueDatabase::class.java)

    override suspend fun enqueue(candidate: ModerationCandidate): ModerationItem? {
        val item = try {
            repository.enqueue(candidate)
        } catch (ex: ExposedSQLException) {
            logger.info("Moderation candidate already queued for cluster {}", candidate.clusterKey)
            return null
        }
        if (item == null) return null
        val text = ModerationTemplates.renderAdminCard(candidate)
        val markup = buildKeyboard(item.moderationId.toString())
        val request = SendMessage(config.adminChatId, text)
            .parseMode(ParseMode.MarkdownV2)
            .replyMarkup(markup)
        config.adminThreadId?.let { request.messageThreadId(it.toInt()) }
        val response: SendResponse = bot.execute(request)
        if (!response.isOk) {
            logger.warn("Failed to send moderation card for cluster {}", candidate.clusterKey)
            return item
        }
        val messageId = response.message()?.messageId()
        if (messageId != null) {
            repository.markCardSent(item.moderationId, config.adminChatId, config.adminThreadId, messageId.toLong())
        }
        return item
    }

    override suspend fun isSourceMuted(domain: String, now: java.time.Instant): Boolean {
        return repository.isSourceMuted(domain, now)
    }

    override suspend fun isEntityMuted(entity: String, now: java.time.Instant): Boolean {
        return repository.isEntityMuted(entity, now)
    }

    private fun buildKeyboard(id: String): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton("✅ Publish now").callbackData("mod:$id:publish"),
                InlineKeyboardButton("🧺 Add to digest").callbackData("mod:$id:digest")
            ),
            arrayOf(
                InlineKeyboardButton("❌ Ignore").callbackData("mod:$id:ignore"),
                InlineKeyboardButton("✏️ Edit").callbackData("mod:$id:edit")
            ),
            arrayOf(
                InlineKeyboardButton("🔕 Mute source 24h").callbackData("mod:$id:mute_source"),
                InlineKeyboardButton("🔕 Mute entity 24h").callbackData("mod:$id:mute_entity")
            )
        )
    }
}
