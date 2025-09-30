package billing.bot

import billing.model.BillingPlan
import billing.model.Tier
import billing.model.UserSubscription
import billing.service.BillingService
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.LinkPreviewOptions
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

object StarsBotCommands {
    private val logger = LoggerFactory.getLogger(StarsBotCommands::class.java)

    private object UpdateGuard {
        private const val TTL_MS: Long = 5 * 60 * 1000
        private val processed = ConcurrentHashMap<Int, Long>()

        fun tryAcquire(update: Update): Boolean {
            val updateId = update.updateId()
            val now = System.currentTimeMillis()
            cleanup(now)
            val existing = processed.putIfAbsent(updateId, now)
            if (existing == null) {
                return true
            }
            if (now - existing > TTL_MS) {
                processed[updateId] = now
                return true
            }
            logger.debug("duplicate_update id={}", updateId)
            return false
        }

        private fun cleanup(now: Long) {
            processed.entries.removeIf { now - it.value > TTL_MS }
        }
    }

    suspend fun handlePlans(update: Update, bot: TelegramBot, svc: BillingService) {
        if (!UpdateGuard.tryAcquire(update)) {
            return
        }
        val message = update.message() ?: return
        val chatId = message.chat()?.id() ?: return
        val response = withContext(Dispatchers.IO) { svc.listPlans() }
        val text = response.fold(
            onSuccess = { plans -> formatPlans(plans) },
            onFailure = { _ -> "Try later" }
        )
        bot.execute(
            SendMessage(chatId, text)
                .parseMode(ParseMode.Markdown)
                .linkPreviewOptions(com.pengrad.telegrambot.model.LinkPreviewOptions().isDisabled(true))
        )
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun handleBuy(update: Update, bot: TelegramBot, svc: BillingService) {
        if (!UpdateGuard.tryAcquire(update)) {
            return
        }
        val message = update.message() ?: return
        val chatId = message.chat()?.id() ?: return
        val keyboard = InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton("Buy PRO").callbackData("buy:PRO"),
                InlineKeyboardButton("Buy PRO+").callbackData("buy:PRO_PLUS"),
                InlineKeyboardButton("Buy VIP").callbackData("buy:VIP")
            )
        )
        bot.execute(
            SendMessage(chatId, "Choose your plan:")
                .replyMarkup(keyboard)
        )
    }

    suspend fun handleStatus(update: Update, bot: TelegramBot, svc: BillingService) {
        if (!UpdateGuard.tryAcquire(update)) {
            return
        }
        val message = update.message() ?: return
        val chatId = message.chat()?.id() ?: return
        val userId = message.from()?.id() ?: return
        val result = withContext(Dispatchers.IO) { svc.getMySubscription(userId) }
        val text = result.fold(
            onSuccess = { subscription -> formatStatus(subscription) },
            onFailure = { _ -> "Try later" }
        )
        bot.execute(
            SendMessage(chatId, text)
                .parseMode(ParseMode.Markdown)
                .linkPreviewOptions(com.pengrad.telegrambot.model.LinkPreviewOptions().isDisabled(true))
        )
    }

    suspend fun handleCallback(update: Update, bot: TelegramBot, svc: BillingService) {
        val callback = update.callbackQuery() ?: return
        val queryId = callback.id()
        val chatId = callback.message()?.chat()?.id()
        val userId = callback.from()?.id()
        val data = callback.data() ?: return
        if (!data.startsWith("buy:", ignoreCase = true)) {
            bot.execute(AnswerCallbackQuery(queryId))
            return
        }
        if (!UpdateGuard.tryAcquire(update)) {
            bot.execute(AnswerCallbackQuery(queryId))
            return
        }
        val tier = parseTier(data.substringAfter(':'))
        if (tier == null || chatId == null || userId == null) {
            bot.execute(AnswerCallbackQuery(queryId))
            return
        }
        val invoice = withContext(Dispatchers.IO) { svc.createInvoiceFor(userId, tier) }
        if (invoice.isSuccess) {
            bot.execute(
                SendMessage(chatId, "Pay via Stars: ${invoice.getOrNull()}")
            )
            bot.execute(
                AnswerCallbackQuery(queryId).text("Invoice sent")
            )
            return
        }
        val error = invoice.exceptionOrNull()
        val message = when (error) {
            is NoSuchElementException, is IllegalArgumentException -> "Plan not found"
            else -> "Try later"
        }
        bot.execute(SendMessage(chatId, message))
        bot.execute(AnswerCallbackQuery(queryId))
    }

    private fun formatPlans(plans: List<BillingPlan>): String {
        val active = plans.filter { it.isActive }
        if (active.isEmpty()) {
            return "*Available plans*\nNo active plans"
        }
        val header = "*Available plans*"
        val lines = active.map { plan ->
            val tierLabel = humanTier(plan.tier)
            "• *$tierLabel* — ${plan.title} — ${plan.priceXtr.value} XTR"
        }
        return buildString {
            appendLine(header)
            lines.forEachIndexed { index, line ->
                if (index == lines.lastIndex) {
                    append(line)
                } else {
                    appendLine(line)
                }
            }
        }
    }

    private fun formatStatus(subscription: UserSubscription?): String {
        if (subscription == null) {
            return "*Tier:* FREE\n*Status:* EXPIRED\n*Expires:* —"
        }
        val expires = subscription.expiresAt?.let(Instant::toString) ?: "—"
        val tier = humanTier(subscription.tier)
        return "*Tier:* $tier\n*Status:* ${subscription.status.name}\n*Expires:* $expires"
    }

    private fun humanTier(tier: Tier): String = when (tier) {
        Tier.FREE -> "FREE"
        Tier.PRO -> "PRO"
        Tier.PRO_PLUS -> "PRO+"
        Tier.VIP -> "VIP"
    }

    private fun parseTier(raw: String): Tier? {
        val normalized = raw.trim().uppercase(Locale.ROOT)
        return runCatching { Tier.valueOf(normalized) }.getOrNull()
    }
}
