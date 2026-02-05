package model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant

@Serializable
data class PricePoint(
    val instrumentId: Long,
    @Contextual val ts: Instant,
    @Contextual val price: BigDecimal,
    val ccy: String,
    val source: String,
)
