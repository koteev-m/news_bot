package news.moderation

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import db.DatabaseFactory
import db.tables.PostStatsTable
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import news.config.NewsConfig
import news.render.ModerationTemplates
import org.jetbrains.exposed.sql.insert
import org.slf4j.LoggerFactory

class ModerationBotHandler(
    private val bot: TelegramBot,
    private val repository: ModerationRepository,
    private val newsConfig: NewsConfig,
    private val config: ModerationBotConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ModerationBotHandler::class.java)

    suspend fun handleUpdate(update: Update): Boolean {
        val callback = update.callbackQuery()
        if (callback != null && callback.data()?.startsWith("mod:") == true) {
            val chatId = callback.message()?.chat()?.id()
            if (chatId != config.adminChatId) {
                bot.execute(AnswerCallbackQuery(callback.id()))
                return true
            }
            handleCallback(callback.id(), callback.data()!!)
            return true
        }
        val message = update.message() ?: return false
        if (message.chat()?.id() != config.adminChatId) return false
        val replyTo = message.replyToMessage() ?: return false
        val text = message.text()?.trim()
        if (text.isNullOrBlank()) return false
        val moderationItem = repository.findByAdminMessageId(replyTo.messageId().toLong()) ?: return false
        return handleEditReply(moderationItem, text, message.messageId())
    }

    private suspend fun handleCallback(queryId: String, data: String) {
        val parts = data.split(':')
        if (parts.size < 3) {
            bot.execute(AnswerCallbackQuery(queryId))
            return
        }
        val moderationId = runCatching { UUID.fromString(parts[1]) }.getOrNull()
        val action = parts[2]
        if (moderationId == null) {
            bot.execute(AnswerCallbackQuery(queryId))
            return
        }
        when (action) {
            "publish" -> publishNow(moderationId, queryId)
            "digest" -> markAction(moderationId, queryId, ModerationStatus.DIGEST)
            "ignore" -> markAction(moderationId, queryId, ModerationStatus.IGNORED)
            "mute_source" -> muteSource(moderationId, queryId)
            "mute_entity" -> muteEntity(moderationId, queryId)
            "edit" -> requestEdit(moderationId, queryId)
            else -> bot.execute(AnswerCallbackQuery(queryId))
        }
    }

    private suspend fun markAction(moderationId: UUID, actionId: String, status: ModerationStatus) {
        val updated = repository.markActionIfPending(moderationId, actionId, status)
        if (!updated) {
            bot.execute(AnswerCallbackQuery(actionId).text("Уже обработано"))
            return
        }
        bot.execute(AnswerCallbackQuery(actionId).text("Готово"))
    }

    private suspend fun requestEdit(moderationId: UUID, actionId: String) {
        val updated = repository.markEditRequested(moderationId)
        if (!updated) {
            bot.execute(AnswerCallbackQuery(actionId).text("Уже обработано"))
            return
        }
        val item = repository.findByModerationId(moderationId)
        val messageId = item?.adminMessageId
        if (item != null && messageId != null) {
            val request = SendMessage(config.adminChatId, "Ответьте на карточку текстом для публикации")
            config.adminThreadId?.let { request.messageThreadId(it.toInt()) }
            request.replyParameters(ReplyParameters(messageId.toInt()))
            bot.execute(request)
        }
        bot.execute(AnswerCallbackQuery(actionId).text("Жду текст"))
    }

    private suspend fun publishNow(moderationId: UUID, actionId: String) {
        val locked = repository.markActionIfPending(moderationId, actionId, ModerationStatus.PUBLISHING)
        if (!locked) {
            bot.execute(AnswerCallbackQuery(actionId).text("Уже обработано"))
            return
        }
        val item = repository.findByModerationId(moderationId)
        if (item == null) {
            bot.execute(AnswerCallbackQuery(actionId))
            return
        }
        val response = sendToChannel(item.candidate, null)
        if (response != null) {
            repository.markPublished(moderationId, newsConfig.channelId, response, actionId, edited = false)
            bot.execute(AnswerCallbackQuery(actionId).text("Опубликовано"))
        } else {
            repository.markFailed(moderationId, actionId)
            bot.execute(AnswerCallbackQuery(actionId).text("Ошибка публикации"))
        }
    }

    private suspend fun handleEditReply(item: ModerationItem, text: String, replyMessageId: Int): Boolean {
        if (item.status != ModerationStatus.EDIT_REQUESTED && item.status != ModerationStatus.PENDING) {
            return false
        }
        val actionId = "reply-$replyMessageId"
        val locked = repository.markActionIfPending(item.moderationId, actionId, ModerationStatus.PUBLISHING, text)
        if (!locked) return false
        val response = sendToChannel(item.candidate, text)
        if (response != null) {
            repository.markPublished(item.moderationId, newsConfig.channelId, response, actionId, edited = true)
            return true
        }
        repository.markFailed(item.moderationId, actionId)
        return false
    }

    private suspend fun muteSource(moderationId: UUID, actionId: String) {
        val item = repository.findByModerationId(moderationId)
        if (item == null) {
            bot.execute(AnswerCallbackQuery(actionId))
            return
        }
        val updated = repository.markActionIfPending(moderationId, actionId, ModerationStatus.MUTED_SOURCE)
        if (!updated) {
            bot.execute(AnswerCallbackQuery(actionId).text("Уже обработано"))
            return
        }
        repository.upsertMuteSource(item.candidate.sourceDomain, mutedUntil())
        bot.execute(AnswerCallbackQuery(actionId).text("Источник замьючен"))
    }

    private suspend fun muteEntity(moderationId: UUID, actionId: String) {
        val item = repository.findByModerationId(moderationId)
        if (item == null) {
            bot.execute(AnswerCallbackQuery(actionId))
            return
        }
        val entity = item.candidate.primaryEntityHash
        if (entity == null) {
            bot.execute(AnswerCallbackQuery(actionId).text("Нет сущности"))
            return
        }
        val updated = repository.markActionIfPending(moderationId, actionId, ModerationStatus.MUTED_ENTITY)
        if (!updated) {
            bot.execute(AnswerCallbackQuery(actionId).text("Уже обработано"))
            return
        }
        repository.upsertMuteEntity(entity, mutedUntil())
        bot.execute(AnswerCallbackQuery(actionId).text("Сущность замьючена"))
    }

    private suspend fun sendToChannel(candidate: ModerationCandidate, editedText: String?): Long? {
        val text = if (editedText != null) {
            ModerationTemplates.renderEditedPost(editedText, candidate.deepLink)
        } else {
            ModerationTemplates.renderBreakingPost(candidate)
        }
        return withContext(Dispatchers.IO) {
            val request = SendMessage(newsConfig.channelId, text)
                .parseMode(ParseMode.MarkdownV2)
            val response: SendResponse = bot.execute(request)
            if (!response.isOk) {
                logger.warn("Moderation publish failed for cluster {}", candidate.clusterKey)
                return@withContext null
            }
            val messageId = response.message()?.messageId()
            if (messageId != null) {
                recordPostStats(candidate.clusterId, newsConfig.channelId, messageId)
            }
            messageId?.toLong()
        }
    }

    private suspend fun recordPostStats(clusterId: UUID, channelId: Long, messageId: Int) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        DatabaseFactory.dbQuery {
            PostStatsTable.insert {
                it[PostStatsTable.channelId] = channelId
                it[PostStatsTable.messageId] = messageId.toLong()
                it[PostStatsTable.clusterId] = clusterId
                it[PostStatsTable.postedAt] = now
            }
        }
    }

    private fun mutedUntil(): Instant {
        return clock.instant().plusSeconds(config.muteHours * 3600)
    }
}
