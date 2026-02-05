package app

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramWebhookConfigTest {
    @Test
    fun `defaults enable webhook and disables fail-fast`() {
        val config = MapApplicationConfig()

        val parsed = TelegramWebhookConfig.from(config)

        assertTrue(parsed.enabled)
        assertFalse(parsed.failFastOnMissingSecret)
        assertNull(parsed.secret)
    }

    @Test
    fun `parses webhook flags and trims secret`() {
        val config =
            MapApplicationConfig().apply {
                put("telegram.webhook.enabled", "false")
                put("telegram.webhook.failFastOnMissingSecret", "true")
                put("telegram.webhookSecret", "  secret ")
            }

        val parsed = TelegramWebhookConfig.from(config)

        assertFalse(parsed.enabled)
        assertTrue(parsed.failFastOnMissingSecret)
        assertEquals("secret", parsed.secret)
    }
}
