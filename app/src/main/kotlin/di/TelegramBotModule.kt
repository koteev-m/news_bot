package di

import com.pengrad.telegrambot.TelegramBot
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

private val TelegramBotKey: AttributeKey<TelegramBot> = AttributeKey("TelegramBot")

private val logger = LoggerFactory.getLogger("TelegramBotModule")

fun Application.ensureTelegramBot(): TelegramBot {
    if (attributes.contains(TelegramBotKey)) {
        return attributes[TelegramBotKey]
    }

    val token = environment.config.propertyOrNull("telegram.botToken")?.getString()?.trim()
        ?: throw IllegalStateException("telegram.botToken is not configured")
    require(token.isNotEmpty()) { "telegram.botToken must not be blank" }

    val bot = TelegramBot(token)
    attributes.put(TelegramBotKey, bot)
    logger.info("telegram-bot initialized")
    return bot
}

fun Application.telegramBot(): TelegramBot = ensureTelegramBot()
