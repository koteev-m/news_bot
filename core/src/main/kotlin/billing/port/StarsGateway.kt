package billing.port

import billing.model.Tier

interface StarsGateway {
    suspend fun createInvoiceLink(tier: Tier, priceXtr: Long, payload: String): Result<String>
}
