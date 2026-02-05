package model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Serializable
data class PositionDto(
    @Contextual val portfolioId: UUID,
    val instrumentId: Long,
    @Contextual val qty: BigDecimal,
    @Contextual val avgPrice: BigDecimal?,
    val avgPriceCcy: String?,
    @Contextual val updatedAt: Instant,
)
