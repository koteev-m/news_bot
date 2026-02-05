package news.pipeline

import db.DatabaseFactory
import db.tables.PublishJobsTable
import news.metrics.NewsMetricsPort
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DbPublishJobRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val metrics: NewsMetricsPort = NewsMetricsPort.Noop,
) : PublishJobQueue {
    private val logger = LoggerFactory.getLogger(DbPublishJobRepository::class.java)
    private val processingTimeout = Duration.ofMinutes(15)

    override suspend fun enqueue(job: PublishJobRequest): PublishJob? {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val jobId = UUID.randomUUID()
        return try {
            DatabaseFactory.dbQuery {
                PublishJobsTable.insert {
                    it[PublishJobsTable.jobId] = jobId
                    it[clusterId] = job.clusterId
                    it[clusterKey] = job.clusterKey
                    it[target] = job.target.name
                    it[status] = job.status.name
                    it[scheduledAt] = OffsetDateTime.ofInstant(job.scheduledAt, ZoneOffset.UTC)
                    it[createdAt] = OffsetDateTime.ofInstant(job.createdAt, ZoneOffset.UTC)
                    it[updatedAt] = now
                    it[publishedAt] = job.publishedAt?.let { value -> OffsetDateTime.ofInstant(value, ZoneOffset.UTC) }
                    it[processingOwner] = null
                    it[title] = job.title
                    it[summary] = job.summary
                    it[sourceDomain] = job.sourceDomain
                    it[topics] = job.topics.joinToString(",")
                    it[deepLink] = job.deepLink
                }
            }
            metrics.incPublishJobStatus(job.status)
            job.toPublishJob(jobId, job.status, job.publishedAt)
        } catch (ex: ExposedSQLException) {
            if (!isUniqueViolation(ex)) {
                throw ex
            }
            val existing = findByClusterAndTarget(job.clusterId, job.target)
            if (existing == null) {
                logger.warn("Publish job conflict without existing row for cluster {}", job.clusterId, ex)
                return null
            }
            existing
        }
    }

    override suspend fun claimDueDigestJobs(
        now: Instant,
        limit: Int,
        owner: String,
    ): List<PublishJob> {
        val nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        val reclaimBefore = OffsetDateTime.ofInstant(now.minus(processingTimeout), ZoneOffset.UTC)
        val (reclaimed, due) =
            DatabaseFactory.dbQuery {
                val reclaimed =
                    PublishJobsTable.update({
                        (PublishJobsTable.status eq PublishJobStatus.PROCESSING.name) and
                            (PublishJobsTable.target eq PublishTarget.DIGEST.name) and
                            (PublishJobsTable.updatedAt lessEq reclaimBefore)
                    }) {
                        it[PublishJobsTable.status] = PublishJobStatus.PENDING.name
                        it[PublishJobsTable.processingOwner] = null
                        it[PublishJobsTable.updatedAt] = nowTs
                    }
                val due =
                    PublishJobsTable
                        .select {
                            (PublishJobsTable.status eq PublishJobStatus.PENDING.name) and
                                (PublishJobsTable.target eq PublishTarget.DIGEST.name) and
                                (PublishJobsTable.scheduledAt lessEq nowTs)
                        }.orderBy(PublishJobsTable.scheduledAt to org.jetbrains.exposed.sql.SortOrder.ASC)
                        .limit(limit)
                        .map { it[PublishJobsTable.jobId] }
                reclaimed to due
            }
        if (reclaimed > 0) {
            logger.info("Reclaimed {} digest publish jobs stuck in processing", reclaimed)
            metrics.incPublishJobStatus(PublishJobStatus.PENDING, reclaimed)
        }
        if (due.isEmpty()) return emptyList()
        val updated =
            DatabaseFactory.dbQuery {
                PublishJobsTable.update({
                    (PublishJobsTable.jobId inList due) and
                        (PublishJobsTable.status eq PublishJobStatus.PENDING.name)
                }) {
                    it[PublishJobsTable.status] = PublishJobStatus.PROCESSING.name
                    it[PublishJobsTable.processingOwner] = owner
                    it[PublishJobsTable.updatedAt] = nowTs
                }
            }
        if (updated > 0) {
            metrics.incPublishJobStatus(PublishJobStatus.PROCESSING, updated)
        }
        if (updated == 0) return emptyList()
        return DatabaseFactory.dbQuery {
            PublishJobsTable
                .select {
                    (PublishJobsTable.jobId inList due) and
                        (PublishJobsTable.status eq PublishJobStatus.PROCESSING.name) and
                        (PublishJobsTable.processingOwner eq owner)
                }.map { rowToPublishJob(it) }
        }
    }

    override suspend fun markJobsStatus(
        jobIds: List<UUID>,
        status: PublishJobStatus,
        publishedAt: Instant?,
    ) {
        if (jobIds.isEmpty()) return
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val updated =
            DatabaseFactory.dbQuery {
                PublishJobsTable.update({ PublishJobsTable.jobId inList jobIds }) {
                    it[PublishJobsTable.status] = status.name
                    it[PublishJobsTable.updatedAt] = now
                    it[PublishJobsTable.processingOwner] = null
                    if (publishedAt != null) {
                        it[PublishJobsTable.publishedAt] = OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC)
                    }
                }
            }
        if (updated > 0) {
            metrics.incPublishJobStatus(status, updated)
        }
    }

    override suspend fun markPendingDigestSkipped(clusterId: UUID) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val updated =
            DatabaseFactory.dbQuery {
                PublishJobsTable.update({
                    (PublishJobsTable.clusterId eq clusterId) and
                        (PublishJobsTable.target eq PublishTarget.DIGEST.name) and
                        (PublishJobsTable.status eq PublishJobStatus.PENDING.name)
                }) {
                    it[PublishJobsTable.status] = PublishJobStatus.SKIPPED.name
                    it[PublishJobsTable.updatedAt] = now
                }
            }
        if (updated > 0) {
            metrics.incPublishJobStatus(PublishJobStatus.SKIPPED, updated)
        }
    }

    override suspend fun lastBreakingPublishedAt(): Instant? =
        DatabaseFactory.dbQuery {
            PublishJobsTable
                .select {
                    (PublishJobsTable.target eq PublishTarget.BREAKING.name) and
                        (PublishJobsTable.status eq PublishJobStatus.PUBLISHED.name)
                }.orderBy(PublishJobsTable.publishedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(1)
                .mapNotNull { it[PublishJobsTable.publishedAt]?.toInstant() }
                .firstOrNull()
        }

    override suspend fun countBreakingPublishedSince(since: Instant): Int {
        val sinceTs = OffsetDateTime.ofInstant(since, ZoneOffset.UTC)
        return DatabaseFactory.dbQuery {
            PublishJobsTable
                .select {
                    (PublishJobsTable.target eq PublishTarget.BREAKING.name) and
                        (PublishJobsTable.status eq PublishJobStatus.PUBLISHED.name) and
                        (PublishJobsTable.publishedAt greaterEq sinceTs)
                }.count()
                .toInt()
        }
    }

    private suspend fun findByClusterAndTarget(
        clusterId: UUID,
        target: PublishTarget,
    ): PublishJob? =
        DatabaseFactory.dbQuery {
            PublishJobsTable
                .select {
                    (PublishJobsTable.clusterId eq clusterId) and (PublishJobsTable.target eq target.name)
                }.limit(1)
                .map { rowToPublishJob(it) }
                .firstOrNull()
        }

    private fun rowToPublishJob(row: org.jetbrains.exposed.sql.ResultRow): PublishJob =
        PublishJob(
            jobId = row[PublishJobsTable.jobId],
            clusterId = row[PublishJobsTable.clusterId],
            clusterKey = row[PublishJobsTable.clusterKey],
            target = PublishTarget.valueOf(row[PublishJobsTable.target]),
            scheduledAt = row[PublishJobsTable.scheduledAt].toInstant(),
            status = PublishJobStatus.valueOf(row[PublishJobsTable.status]),
            title = row[PublishJobsTable.title],
            summary = row[PublishJobsTable.summary],
            sourceDomain = row[PublishJobsTable.sourceDomain],
            topics = parseTopics(row[PublishJobsTable.topics]),
            deepLink = row[PublishJobsTable.deepLink],
            createdAt = row[PublishJobsTable.createdAt].toInstant(),
            publishedAt = row[PublishJobsTable.publishedAt]?.toInstant(),
        )

    private fun parseTopics(raw: String): Set<String> =
        raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun PublishJobRequest.toPublishJob(
        jobId: UUID,
        status: PublishJobStatus,
        publishedAt: Instant?,
    ): PublishJob =
        PublishJob(
            jobId = jobId,
            clusterId = clusterId,
            clusterKey = clusterKey,
            target = target,
            scheduledAt = scheduledAt,
            status = status,
            title = title,
            summary = summary,
            sourceDomain = sourceDomain,
            topics = topics,
            deepLink = deepLink,
            createdAt = createdAt,
            publishedAt = publishedAt,
        )

    private fun isUniqueViolation(ex: ExposedSQLException): Boolean {
        val sqlState = ex.sqlState ?: (ex.cause as? SQLException)?.sqlState
        return sqlState == "23505"
    }
}
