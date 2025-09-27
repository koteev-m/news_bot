package billing.bot

import com.pengrad.telegrambot.model.Update
import java.util.Locale

object StarsBotRouter {
    sealed class BotRoute {
        data object Plans : BotRoute()
        data object Buy : BotRoute()
        data object Status : BotRoute()
        data object Callback : BotRoute()
        data object Unknown : BotRoute()
    }

    fun route(update: Update): BotRoute {
        val callbackData = update.callbackQuery()?.data()
        if (callbackData != null && callbackData.startsWith("buy:", ignoreCase = true)) {
            return BotRoute.Callback
        }

        val message = update.message() ?: return BotRoute.Unknown
        val rawText = message.text() ?: return BotRoute.Unknown
        val command = rawText.trim().takeWhile { !it.isWhitespace() }
        if (command.isEmpty()) {
            return BotRoute.Unknown
        }
        val normalized = command.substringBefore('@').lowercase(Locale.ROOT)
        return when (normalized) {
            "/plans" -> BotRoute.Plans
            "/buy" -> BotRoute.Buy
            "/status" -> BotRoute.Status
            else -> BotRoute.Unknown
        }
    }
}
