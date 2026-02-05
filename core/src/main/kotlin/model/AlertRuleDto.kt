package model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Serializable
data class AlertRuleDto(
    @Contextual val ruleId: UUID,
    val userId: Long?,
    @Contextual val portfolioId: UUID?,
    val instrumentId: Long?,
    val topic: String?,
    val kind: String,
    val windowMinutes: Int,
    @Contextual val threshold: BigDecimal,
    val enabled: Boolean,
    val cooldownMinutes: Int,
    @Contextual val hysteresis: BigDecimal,
    val quietHoursJson: String?,
    @Contextual val createdAt: Instant,
)
