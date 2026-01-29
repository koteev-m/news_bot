package news.pipeline

import java.time.Instant
import java.util.UUID

enum class PublishTarget {
    DIGEST,
    BREAKING,
    REVIEW
}

enum class PublishJobStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    SKIPPED,
    FAILED
}

data class PublishJobRequest(
    val clusterId: UUID,
    val clusterKey: String,
    val target: PublishTarget,
    val scheduledAt: Instant,
    val title: String,
    val summary: String?,
    val sourceDomain: String,
    val topics: Set<String>,
    val deepLink: String,
    val createdAt: Instant,
    val status: PublishJobStatus = PublishJobStatus.PENDING,
    val publishedAt: Instant? = null,
)

data class PublishJob(
    val jobId: UUID,
    val clusterId: UUID,
    val clusterKey: String,
    val target: PublishTarget,
    val scheduledAt: Instant,
    val status: PublishJobStatus,
    val title: String,
    val summary: String?,
    val sourceDomain: String,
    val topics: Set<String>,
    val deepLink: String,
    val createdAt: Instant,
    val publishedAt: Instant?,
)

interface PublishJobQueue : BreakingHistory {
    suspend fun enqueue(job: PublishJobRequest): PublishJob?
    suspend fun claimDueDigestJobs(now: Instant, limit: Int, owner: String): List<PublishJob>
    suspend fun markJobsStatus(
        jobIds: List<UUID>,
        status: PublishJobStatus,
        publishedAt: Instant? = null,
    )

    suspend fun markPendingDigestSkipped(clusterId: UUID)

    object Noop : PublishJobQueue {
        override suspend fun enqueue(job: PublishJobRequest): PublishJob? = null
        override suspend fun claimDueDigestJobs(now: Instant, limit: Int, owner: String): List<PublishJob> = emptyList()
        override suspend fun markJobsStatus(jobIds: List<UUID>, status: PublishJobStatus, publishedAt: Instant?) = Unit
        override suspend fun markPendingDigestSkipped(clusterId: UUID) = Unit
        override suspend fun lastBreakingPublishedAt(): Instant? = null
        override suspend fun countBreakingPublishedSince(since: Instant): Int = 0
    }
}
