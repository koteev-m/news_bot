package news.pipeline

import db.DatabaseFactory
import db.tables.PublishJobsTable
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class DbPublishJobRepository(
    private val clock: Clock = Clock.systemUTC(),
) : PublishJobQueue {
    private val logger = LoggerFactory.getLogger(DbPublishJobRepository::class.java)

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

    override suspend fun claimDueDigestJobs(now: Instant, limit: Int, owner: String): List<PublishJob> {
        val due = DatabaseFactory.dbQuery {
            PublishJobsTable.select {
                (PublishJobsTable.status eq PublishJobStatus.PENDING.name) and
                    (PublishJobsTable.target eq PublishTarget.DIGEST.name) and
                    (PublishJobsTable.scheduledAt lessEq OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            }.orderBy(PublishJobsTable.scheduledAt to org.jetbrains.exposed.sql.SortOrder.ASC)
                .limit(limit)
                .map { it[PublishJobsTable.jobId] }
        }
        if (due.isEmpty()) return emptyList()
        val updated = DatabaseFactory.dbQuery {
            PublishJobsTable.update({
                (PublishJobsTable.jobId inList due) and
                    (PublishJobsTable.status eq PublishJobStatus.PENDING.name)
            }) {
                it[PublishJobsTable.status] = PublishJobStatus.PROCESSING.name
                it[PublishJobsTable.processingOwner] = owner
                it[PublishJobsTable.updatedAt] = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
            }
        }
        if (updated == 0) return emptyList()
        return DatabaseFactory.dbQuery {
            PublishJobsTable.select {
                (PublishJobsTable.jobId inList due) and
                    (PublishJobsTable.status eq PublishJobStatus.PROCESSING.name) and
                    (PublishJobsTable.processingOwner eq owner)
            }.map { rowToPublishJob(it) }
        }
    }

    override suspend fun markJobsStatus(jobIds: List<UUID>, status: PublishJobStatus, publishedAt: Instant?) {
        if (jobIds.isEmpty()) return
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
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
    }

    override suspend fun markPendingDigestSkipped(clusterId: UUID) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
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
    }

    override suspend fun lastBreakingPublishedAt(): Instant? {
        return DatabaseFactory.dbQuery {
            PublishJobsTable.select {
                (PublishJobsTable.target eq PublishTarget.BREAKING.name) and
                    (PublishJobsTable.status eq PublishJobStatus.PUBLISHED.name)
            }.orderBy(PublishJobsTable.publishedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(1)
                .mapNotNull { it[PublishJobsTable.publishedAt]?.toInstant() }
                .firstOrNull()
        }
    }

    override suspend fun countBreakingPublishedSince(since: Instant): Int {
        val sinceTs = OffsetDateTime.ofInstant(since, ZoneOffset.UTC)
        return DatabaseFactory.dbQuery {
            PublishJobsTable.select {
                (PublishJobsTable.target eq PublishTarget.BREAKING.name) and
                    (PublishJobsTable.status eq PublishJobStatus.PUBLISHED.name) and
                    (PublishJobsTable.publishedAt greaterEq sinceTs)
            }.count()
        }
    }

    private suspend fun findByClusterAndTarget(clusterId: UUID, target: PublishTarget): PublishJob? {
        return DatabaseFactory.dbQuery {
            PublishJobsTable.select {
                (PublishJobsTable.clusterId eq clusterId) and (PublishJobsTable.target eq target.name)
            }.limit(1)
                .map { rowToPublishJob(it) }
                .firstOrNull()
        }
    }

    private fun rowToPublishJob(row: org.jetbrains.exposed.sql.ResultRow): PublishJob {
        return PublishJob(
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
    }

    private fun parseTopics(raw: String): Set<String> {
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun PublishJobRequest.toPublishJob(
        jobId: UUID,
        status: PublishJobStatus,
        publishedAt: Instant?,
    ): PublishJob {
        return PublishJob(
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
    }

    private fun isUniqueViolation(ex: ExposedSQLException): Boolean {
        val sqlState = ex.sqlState ?: (ex.cause as? SQLException)?.sqlState
        return sqlState == "23505"
    }
}
