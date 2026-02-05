package residency

import kotlinx.serialization.Serializable

@Serializable
data class ResidencyPolicy(
    val tenantId: Long,
    val region: String, // 'EU'|'US'|'AP'
    val dataClasses: List<String>, // enabled classes
)

enum class DataClass { PII, FIN, LOGS, METRICS }
