package news.pipeline

import db.tables.PublishJobsTable
import it.TestDb
import kotlinx.coroutines.runBlocking
import news.metrics.NewsMetricsPort
import org.jetbrains.exposed.sql.insert
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@Tag("integration")
class DbPublishJobRepositoryMetricsIT {
    @Test
    fun `metrics counters reflect updated rows`() =
        runBlocking {
            TestDb.withMigratedDatabase { _ ->
                val metrics = RecordingNewsMetrics()
                val repository = DbPublishJobRepository(clock = Clock.systemUTC(), metrics = metrics)
                val now = Instant.now()
                insertJob(
                    target = PublishTarget.DIGEST,
                    status = PublishJobStatus.PROCESSING,
                    scheduledAt = now.minusSeconds(60),
                    updatedAt = now.minusSeconds(20 * 60),
                    processingOwner = "stale-owner",
                )
                insertJob(
                    target = PublishTarget.DIGEST,
                    status = PublishJobStatus.PENDING,
                    scheduledAt = now.minusSeconds(60),
                    updatedAt = now.minusSeconds(30),
                )

                val claimed = repository.claimDueDigestJobs(now, limit = 10, owner = "worker-1")
                assertEquals(2, claimed.size)

                repository.markJobsStatus(
                    jobIds = claimed.map { it.jobId },
                    status = PublishJobStatus.PUBLISHED,
                    publishedAt = now,
                )

                val skipped =
                    insertJob(
                        target = PublishTarget.DIGEST,
                        status = PublishJobStatus.PENDING,
                        scheduledAt = now.minusSeconds(120),
                        updatedAt = now.minusSeconds(10),
                    )
                repository.markPendingDigestSkipped(skipped.clusterId)

                assertEquals(1, metrics.totalFor(PublishJobStatus.PENDING))
                assertEquals(2, metrics.totalFor(PublishJobStatus.PROCESSING))
                assertEquals(2, metrics.totalFor(PublishJobStatus.PUBLISHED))
                assertEquals(1, metrics.totalFor(PublishJobStatus.SKIPPED))
            }
        }

    private suspend fun insertJob(
        target: PublishTarget,
        status: PublishJobStatus,
        scheduledAt: Instant,
        updatedAt: Instant,
        processingOwner: String? = null,
    ): JobIdentity {
        val jobId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        TestDb.tx {
            PublishJobsTable.insert {
                it[PublishJobsTable.jobId] = jobId
                it[PublishJobsTable.clusterId] = clusterId
                it[PublishJobsTable.clusterKey] = "cluster-$clusterId"
                it[PublishJobsTable.target] = target.name
                it[PublishJobsTable.status] = status.name
                it[PublishJobsTable.scheduledAt] = OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC)
                it[PublishJobsTable.createdAt] = OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC)
                it[PublishJobsTable.updatedAt] = OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC)
                it[PublishJobsTable.publishedAt] = null
                it[PublishJobsTable.processingOwner] = processingOwner
                it[PublishJobsTable.title] = "title"
                it[PublishJobsTable.summary] = "summary"
                it[PublishJobsTable.sourceDomain] = "example.com"
                it[PublishJobsTable.topics] = "economy"
                it[PublishJobsTable.deepLink] = "deeplink"
            }
        }
        return JobIdentity(jobId, clusterId)
    }

    private data class JobIdentity(
        val jobId: UUID,
        val clusterId: UUID,
    )

    private class RecordingNewsMetrics : NewsMetricsPort {
        private val publishJobStatus = mutableListOf<Pair<PublishJobStatus, Int>>()

        override fun incPublish(
            type: news.metrics.NewsPublishType,
            result: news.metrics.NewsPublishResult,
        ) {}

        override fun incEdit() {}

        override fun incCandidatesReceived(
            sourceId: String,
            count: Int,
        ) {}

        override fun incClustersCreated(eventType: news.model.EventType) {}

        override fun incRouted(route: news.routing.EventRoute) {}

        override fun incDropped(reason: news.routing.DropReason) {}

        override fun incPublishJobStatus(
            status: PublishJobStatus,
            count: Int,
        ) {
            publishJobStatus += status to count
        }

        override fun incModerationQueueStatus(
            status: news.moderation.ModerationStatus,
            count: Int,
        ) {}

        override fun setDedupRatio(ratio: Double) {}

        fun totalFor(status: PublishJobStatus): Int = publishJobStatus.filter { it.first == status }.sumOf { it.second }
    }
}
