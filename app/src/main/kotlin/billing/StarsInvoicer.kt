package billing

import io.ktor.client.*

@Suppress("UNUSED_PARAMETER")
class StarsInvoicer(private val http: HttpClient) {
    suspend fun createInvoiceLink(tenantId: Long, totalUsd: Double): String {
        // Мок — реальный вызов к Telegram Stars Billing выполнять не будем
        // Возвращаем фиктивную ссылку
        return "https://t.me/pay?tenant=${tenantId}&amount_usd=${"%.2f".format(totalUsd)}"
    }
}
