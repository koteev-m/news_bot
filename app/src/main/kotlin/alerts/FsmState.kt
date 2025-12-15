package alerts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface FsmState {
    @Serializable
    @SerialName("idle")
    data object IDLE : FsmState

    @Serializable
    @SerialName("armed")
    data class ARMED(val armedAtEpochSec: Long) : FsmState

    @Serializable
    @SerialName("pushed")
    data class PUSHED(val pushedAtEpochSec: Long) : FsmState

    @Serializable
    @SerialName("cooldown")
    data class COOLDOWN(val untilEpochSec: Long) : FsmState

    @Serializable
    @SerialName("quiet")
    data class QUIET(val buffer: List<PendingAlert> = emptyList()) : FsmState

    @Serializable
    @SerialName("budget_exhausted")
    data object BUDGET_EXHAUSTED : FsmState

    @Serializable
    @SerialName("portfolio_summary")
    data object PORTFOLIO_SUMMARY : FsmState
}

@Serializable
data class PendingAlert(
    val classId: String,
    val ticker: String,
    val window: String,
    val score: Double,
    val pctMove: Double,
    val ts: Long
)

@Serializable
data class EmittedAlert(val alert: PendingAlert, val reason: String)

@Serializable
data class TransitionResult(
    val newState: FsmState,
    val emitted: List<EmittedAlert> = emptyList(),
    val suppressedReasons: List<String> = emptyList()
)
