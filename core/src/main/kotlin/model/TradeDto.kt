package model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

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
    @Contextual val createdAt: Instant
)
