package news.moderation

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import news.render.ModerationTemplates
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import java.sql.SQLException

class ModerationQueueDatabase(
    private val repository: ModerationRepository,
    private val bot: TelegramBot,
    private val config: ModerationBotConfig,
) : ModerationQueue {
    private val logger = LoggerFactory.getLogger(ModerationQueueDatabase::class.java)
    private val clusterConstraintName = "uk_moderation_queue_cluster"

    override suspend fun enqueue(candidate: ModerationCandidate): ModerationItem? {
        val item =
            try {
                repository.enqueue(candidate)
            } catch (ex: ExposedSQLException) {
                if (!isUniqueViolation(ex)) {
                    logger.error("Failed to enqueue moderation candidate for cluster {}", candidate.clusterKey, ex)
                    throw ex
                }
                val constraintName = extractConstraintName(ex)
                if (constraintName != null && !constraintName.equals(clusterConstraintName, ignoreCase = true)) {
                    logger.error(
                        "Unexpected unique constraint {} violation for cluster {}",
                        constraintName,
                        candidate.clusterKey,
                        ex,
                    )
                    throw ex
                }
                val existing = repository.findByClusterKey(candidate.clusterKey)
                if (existing == null) {
                    logger.error(
                        "Unique violation without existing moderation item for cluster {}",
                        candidate.clusterKey,
                        ex,
                    )
                    throw ex
                }
                if (shouldRetrySend(existing)) {
                    sendCard(existing)
                    return existing
                }
                return null
            }
        if (item == null) return null
        sendCard(item)
        return item
    }

    override suspend fun isSourceMuted(
        domain: String,
        now: java.time.Instant,
    ): Boolean = repository.isSourceMuted(domain, now)

    override suspend fun isEntityMuted(
        entity: String,
        now: java.time.Instant,
    ): Boolean = repository.isEntityMuted(entity, now)

    private fun buildKeyboard(id: String): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton("✅ Publish now").callbackData("mod:$id:publish"),
                InlineKeyboardButton("🧺 Add to digest").callbackData("mod:$id:digest"),
            ),
            arrayOf(
                InlineKeyboardButton("❌ Ignore").callbackData("mod:$id:ignore"),
                InlineKeyboardButton("✏️ Edit").callbackData("mod:$id:edit"),
            ),
            arrayOf(
                InlineKeyboardButton("🔕 Mute source 24h").callbackData("mod:$id:mute_source"),
                InlineKeyboardButton("🔕 Mute entity 24h").callbackData("mod:$id:mute_entity"),
            ),
        )

    private fun shouldRetrySend(item: ModerationItem): Boolean {
        if (item.adminMessageId != null) return false
        if (item.actionId != null) return false
        return item.status == ModerationStatus.PENDING || item.status == ModerationStatus.EDIT_REQUESTED
    }

    private suspend fun sendCard(item: ModerationItem) {
        val text = ModerationTemplates.renderAdminCard(item.candidate)
        val markup = buildKeyboard(item.moderationId.toString())
        val request =
            SendMessage(config.adminChatId, text)
                .parseMode(ParseMode.MarkdownV2)
                .replyMarkup(markup)
        config.adminThreadId?.let { request.messageThreadId(it.toInt()) }
        val response: SendResponse =
            try {
                withContext(Dispatchers.IO) {
                    bot.execute(request)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.warn("Failed to send moderation card for cluster {}", item.candidate.clusterKey, ex)
                return
            }
        if (!response.isOk) {
            logger.warn("Failed to send moderation card for cluster {}", item.candidate.clusterKey)
            return
        }
        val messageId = response.message()?.messageId()
        if (messageId != null) {
            try {
                repository.markCardSent(
                    item.moderationId,
                    config.adminChatId,
                    config.adminThreadId,
                    messageId.toLong(),
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.error("Failed to mark moderation card sent for cluster {}", item.candidate.clusterKey, ex)
            }
        }
    }

    private fun isUniqueViolation(ex: ExposedSQLException): Boolean {
        val sqlState = ex.sqlState ?: (ex.cause as? SQLException)?.sqlState
        return sqlState == "23505"
    }

    private fun extractConstraintName(ex: ExposedSQLException): String? {
        val message = ex.message ?: ex.cause?.message ?: return null
        val postgresMatch = Regex("constraint \"([^\"]+)\"", RegexOption.IGNORE_CASE).find(message)
        if (postgresMatch != null) {
            return postgresMatch.groupValues.getOrNull(1)
        }
        val mysqlMatch = Regex("key '([^']+)'", RegexOption.IGNORE_CASE).find(message)
        return mysqlMatch?.groupValues?.getOrNull(1)
    }
}
