package model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Serializable
data class TradeDto(
    val tradeId: Long,
    @Contextual val portfolioId: UUID,
    val instrumentId: Long,
    @Contextual val datetime: Instant,
    val side: String,
    @Contextual val quantity: BigDecimal,
    @Contextual val price: BigDecimal,
    val priceCurrency: String,
    @Contextual val fee: BigDecimal,
    val feeCurrency: String,
    @Contextual val tax: BigDecimal?,
    val taxCurrency: String?,
    val broker: String?,
    val note: String?,
    val extId: String?,
    @Contextual val createdAt: Instant,
)
