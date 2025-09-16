package model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PositionDto(
    @Contextual val portfolioId: UUID,
    val instrumentId: Long,
    @Contextual val qty: BigDecimal,
    @Contextual val avgPrice: BigDecimal?,
    val avgPriceCcy: String?,
    @Contextual val updatedAt: Instant
)
