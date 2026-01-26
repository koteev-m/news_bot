package news.moderation

import java.time.Instant

interface ModerationQueue {
    suspend fun enqueue(candidate: ModerationCandidate): ModerationItem?
    suspend fun isSourceMuted(domain: String, now: Instant = Instant.now()): Boolean
    suspend fun isEntityMuted(entity: String, now: Instant = Instant.now()): Boolean

    object Noop : ModerationQueue {
        override suspend fun enqueue(candidate: ModerationCandidate): ModerationItem? = null
        override suspend fun isSourceMuted(domain: String, now: Instant): Boolean = false
        override suspend fun isEntityMuted(entity: String, now: Instant): Boolean = false
    }
}
