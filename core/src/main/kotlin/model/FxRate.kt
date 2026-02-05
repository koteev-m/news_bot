package model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant

@Serializable
data class FxRate(
    val ccy: String,
    @Contextual val ts: Instant,
    @Contextual val rateRub: BigDecimal,
    val source: String,
)
