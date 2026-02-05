package app

import io.ktor.server.config.ApplicationConfig

data class TelegramWebhookConfig(
    val enabled: Boolean,
    val failFastOnMissingSecret: Boolean,
    val secret: String?,
) {
    companion object {
        fun from(config: ApplicationConfig): TelegramWebhookConfig {
            val enabled =
                config
                    .propertyOrNull("telegram.webhook.enabled")
                    ?.getString()
                    ?.toBooleanStrictOrNull()
                    ?: true
            val failFast =
                config
                    .propertyOrNull("telegram.webhook.failFastOnMissingSecret")
                    ?.getString()
                    ?.toBooleanStrictOrNull()
                    ?: false
            val secret =
                config
                    .propertyOrNull("telegram.webhookSecret")
                    ?.getString()
                    ?.trim()
                    ?.ifBlank { null }
            return TelegramWebhookConfig(
                enabled = enabled,
                failFastOnMissingSecret = failFast,
                secret = secret,
            )
        }
    }
}
