package repo.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Payload for inserting new trades. */
data class NewTrade(
    val portfolioId: UUID,
    val instrumentId: Long,
    val datetime: Instant,
    val side: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val priceCurrency: String,
    val fee: BigDecimal,
    val feeCurrency: String,
    val tax: BigDecimal?,
    val taxCurrency: String?,
    val broker: String?,
    val note: String?,
    val extId: String?,
    val createdAt: Instant = Instant.now(),
)

/** Payload for updating a trade. */
data class TradeUpdate(
    val datetime: Instant? = null,
    val side: String? = null,
    val quantity: BigDecimal? = null,
    val price: BigDecimal? = null,
    val priceCurrency: String? = null,
    val fee: BigDecimal? = null,
    val feeCurrency: String? = null,
    val tax: BigDecimal? = null,
    val taxCurrency: String? = null,
    val broker: String? = null,
    val note: String? = null,
)
