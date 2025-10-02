package privacy

import java.time.Instant
import kotlinx.serialization.Serializable

interface PrivacyService {
    suspend fun enqueueErasure(userId: Long)
    suspend fun runErasure(userId: Long, dryRun: Boolean = false): ErasureReport
    suspend fun runRetention(now: Instant = Instant.now()): RetentionReport
}

@Serializable
data class ErasureReport(
    val userId: Long,
    val deleted: Map<String, Long>,
    val anonymized: Map<String, Long>,
    val dryRun: Boolean
)

@Serializable
data class RetentionReport(
    val deletedByTable: Map<String, Long>
)
