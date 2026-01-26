package news.moderation

import java.time.Instant
import java.util.UUID

enum class ModerationSuggestedMode {
    BREAKING,
    DIGEST
}

enum class ModerationStatus {
    PENDING,
    PUBLISHING,
    DIGEST,
    IGNORED,
    MUTED_SOURCE,
    MUTED_ENTITY,
    EDIT_REQUESTED,
    PUBLISHED,
    PUBLISHED_EDITED,
    FAILED
}

data class ModerationCandidate(
    val clusterId: UUID,
    val clusterKey: String,
    val suggestedMode: ModerationSuggestedMode,
    val score: Double,
    val confidence: Double,
    val links: List<String>,
    val sourceDomain: String,
    val entityHashes: List<String>,
    val primaryEntityHash: String?,
    val title: String,
    val summary: String?,
    val topics: Set<String>,
    val deepLink: String,
    val createdAt: Instant,
)

data class ModerationItem(
    val moderationId: UUID,
    val candidate: ModerationCandidate,
    val status: ModerationStatus,
    val adminChatId: Long?,
    val adminThreadId: Long?,
    val adminMessageId: Long?,
    val actionId: String?,
    val editedText: String?,
)
