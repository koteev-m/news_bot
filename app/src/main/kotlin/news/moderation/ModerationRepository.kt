package news.moderation

import db.DatabaseFactory
import db.tables.ModerationQueueTable
import db.tables.MuteEntityTable
import db.tables.MuteSourceTable
import news.metrics.NewsMetricsPort
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ModerationRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val metrics: NewsMetricsPort = NewsMetricsPort.Noop,
) {
    private val logger = LoggerFactory.getLogger(ModerationRepository::class.java)

    suspend fun enqueue(candidate: ModerationCandidate): ModerationItem? {
        val now = OffsetDateTime.ofInstant(candidate.createdAt, ZoneOffset.UTC)
        val moderationId = UUID.randomUUID()
        DatabaseFactory.dbQuery {
            ModerationQueueTable.insert {
                it[ModerationQueueTable.moderationId] = moderationId
                it[clusterId] = candidate.clusterId
                it[clusterKey] = candidate.clusterKey
                it[suggestedMode] = candidate.suggestedMode.name
                it[score] = candidate.score.toBigDecimal()
                it[confidence] = candidate.confidence.toBigDecimal()
                it[links] = candidate.links.joinToString("|")
                it[sourceDomain] = candidate.sourceDomain
                it[entityHashes] = candidate.entityHashes.joinToString("|")
                it[primaryEntityHash] = candidate.primaryEntityHash
                it[title] = candidate.title
                it[summary] = candidate.summary
                it[topics] = candidate.topics.joinToString(",")
                it[deepLink] = candidate.deepLink
                it[createdAt] = now
                it[status] = ModerationStatus.PENDING.name
            }
        }
        metrics.incModerationQueueStatus(ModerationStatus.PENDING)
        logger.info("Moderation candidate queued for cluster {}", candidate.clusterKey)
        return ModerationItem(
            moderationId = moderationId,
            candidate = candidate,
            status = ModerationStatus.PENDING,
            adminChatId = null,
            adminThreadId = null,
            adminMessageId = null,
            actionId = null,
            editedText = null,
        )
    }

    suspend fun findByClusterKey(clusterKey: String): ModerationItem? =
        DatabaseFactory.dbQuery {
            ModerationQueueTable
                .select { ModerationQueueTable.clusterKey eq clusterKey }
                .limit(1)
                .map { row -> rowToItem(row) }
                .firstOrNull()
        }

    suspend fun markCardSent(
        moderationId: UUID,
        adminChatId: Long,
        adminThreadId: Long?,
        adminMessageId: Long,
    ): Boolean =
        DatabaseFactory.dbQuery {
            ModerationQueueTable.update({ ModerationQueueTable.moderationId eq moderationId }) {
                it[ModerationQueueTable.adminChatId] = adminChatId
                it[ModerationQueueTable.adminThreadId] = adminThreadId
                it[ModerationQueueTable.adminMessageId] = adminMessageId
            } > 0
        }

    suspend fun findByModerationId(moderationId: UUID): ModerationItem? =
        DatabaseFactory.dbQuery {
            ModerationQueueTable
                .select { ModerationQueueTable.moderationId eq moderationId }
                .limit(1)
                .map { row -> rowToItem(row) }
                .firstOrNull()
        }

    suspend fun findByAdminMessageId(messageId: Long): ModerationItem? =
        DatabaseFactory.dbQuery {
            ModerationQueueTable
                .select { ModerationQueueTable.adminMessageId eq messageId }
                .limit(1)
                .map { row -> rowToItem(row) }
                .firstOrNull()
        }

    suspend fun markActionIfPending(
        moderationId: UUID,
        expectedActionId: String,
        newStatus: ModerationStatus,
        editedText: String? = null,
    ): Boolean {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val updated =
            DatabaseFactory.dbQuery {
                ModerationQueueTable.update({
                    (ModerationQueueTable.moderationId eq moderationId) and
                        ModerationQueueTable.actionId.isNull()
                }) {
                    it[ModerationQueueTable.status] = newStatus.name
                    it[ModerationQueueTable.actionId] = expectedActionId
                    it[ModerationQueueTable.actionAt] = now
                    it[ModerationQueueTable.editedText] = editedText
                }
            }
        if (updated > 0) {
            metrics.incModerationQueueStatus(newStatus)
        }
        return updated > 0
    }

    suspend fun markEditRequested(moderationId: UUID): Boolean {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val updated =
            DatabaseFactory.dbQuery {
                ModerationQueueTable.update({
                    (ModerationQueueTable.moderationId eq moderationId) and
                        (ModerationQueueTable.status eq ModerationStatus.PENDING.name)
                }) {
                    it[ModerationQueueTable.status] = ModerationStatus.EDIT_REQUESTED.name
                    it[ModerationQueueTable.editRequestedAt] = now
                }
            }
        if (updated > 0) {
            metrics.incModerationQueueStatus(ModerationStatus.EDIT_REQUESTED)
        }
        return updated > 0
    }

    suspend fun markFailed(
        moderationId: UUID,
        actionId: String,
    ): Boolean {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val updated =
            DatabaseFactory.dbQuery {
                ModerationQueueTable.update({ ModerationQueueTable.moderationId eq moderationId }) {
                    it[ModerationQueueTable.status] = ModerationStatus.FAILED.name
                    it[ModerationQueueTable.actionId] = actionId
                    it[ModerationQueueTable.actionAt] = now
                }
            }
        if (updated > 0) {
            metrics.incModerationQueueStatus(ModerationStatus.FAILED)
        }
        return updated > 0
    }

    suspend fun markPublished(
        moderationId: UUID,
        channelId: Long,
        messageId: Long,
        actionId: String,
        edited: Boolean,
    ): Boolean {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val status = if (edited) ModerationStatus.PUBLISHED_EDITED else ModerationStatus.PUBLISHED
        val updated =
            DatabaseFactory.dbQuery {
                ModerationQueueTable.update({ ModerationQueueTable.moderationId eq moderationId }) {
                    it[ModerationQueueTable.status] = status.name
                    it[ModerationQueueTable.publishedChannelId] = channelId
                    it[ModerationQueueTable.publishedMessageId] = messageId
                    it[ModerationQueueTable.publishedAt] = now
                    it[ModerationQueueTable.actionId] = actionId
                    it[ModerationQueueTable.actionAt] = now
                }
            }
        if (updated > 0) {
            metrics.incModerationQueueStatus(status)
        }
        return updated > 0
    }

    suspend fun isSourceMuted(
        domain: String,
        now: Instant = clock.instant(),
    ): Boolean {
        val nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        return DatabaseFactory.dbQuery {
            MuteSourceTable
                .select {
                    (MuteSourceTable.sourceDomain eq domain) and (MuteSourceTable.mutedUntil greaterEq nowTs)
                }.any()
        }
    }

    suspend fun isEntityMuted(
        hash: String,
        now: Instant = clock.instant(),
    ): Boolean {
        val nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        return DatabaseFactory.dbQuery {
            MuteEntityTable
                .select {
                    (MuteEntityTable.entityHash eq hash) and (MuteEntityTable.mutedUntil greaterEq nowTs)
                }.any()
        }
    }

    suspend fun upsertMuteSource(
        domain: String,
        mutedUntil: Instant,
    ) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val until = OffsetDateTime.ofInstant(mutedUntil, ZoneOffset.UTC)
        DatabaseFactory.dbQuery {
            val updated =
                MuteSourceTable.update({ MuteSourceTable.sourceDomain eq domain }) {
                    it[MuteSourceTable.mutedUntil] = until
                }
            if (updated == 0) {
                MuteSourceTable.insert {
                    it[MuteSourceTable.sourceDomain] = domain
                    it[MuteSourceTable.createdAt] = now
                    it[MuteSourceTable.mutedUntil] = until
                }
            }
        }
    }

    suspend fun upsertMuteEntity(
        hash: String,
        mutedUntil: Instant,
    ) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val until = OffsetDateTime.ofInstant(mutedUntil, ZoneOffset.UTC)
        DatabaseFactory.dbQuery {
            val updated =
                MuteEntityTable.update({ MuteEntityTable.entityHash eq hash }) {
                    it[MuteEntityTable.mutedUntil] = until
                }
            if (updated == 0) {
                MuteEntityTable.insert {
                    it[MuteEntityTable.entityHash] = hash
                    it[MuteEntityTable.createdAt] = now
                    it[MuteEntityTable.mutedUntil] = until
                }
            }
        }
    }

    private fun rowToItem(row: org.jetbrains.exposed.sql.ResultRow): ModerationItem {
        val candidate =
            ModerationCandidate(
                clusterId = row[ModerationQueueTable.clusterId],
                clusterKey = row[ModerationQueueTable.clusterKey],
                suggestedMode = ModerationSuggestedMode.valueOf(row[ModerationQueueTable.suggestedMode]),
                score = row[ModerationQueueTable.score].toDouble(),
                confidence = row[ModerationQueueTable.confidence].toDouble(),
                links = row[ModerationQueueTable.links].split('|').filter { it.isNotBlank() },
                sourceDomain = row[ModerationQueueTable.sourceDomain],
                entityHashes = row[ModerationQueueTable.entityHashes].split('|').filter { it.isNotBlank() },
                primaryEntityHash = row[ModerationQueueTable.primaryEntityHash],
                title = row[ModerationQueueTable.title],
                summary = row[ModerationQueueTable.summary],
                topics = row[ModerationQueueTable.topics].split(',').filter { it.isNotBlank() }.toSet(),
                deepLink = row[ModerationQueueTable.deepLink],
                createdAt = row[ModerationQueueTable.createdAt].toInstant(),
            )
        return ModerationItem(
            moderationId = row[ModerationQueueTable.moderationId],
            candidate = candidate,
            status = ModerationStatus.valueOf(row[ModerationQueueTable.status]),
            adminChatId = row[ModerationQueueTable.adminChatId],
            adminThreadId = row[ModerationQueueTable.adminThreadId],
            adminMessageId = row[ModerationQueueTable.adminMessageId],
            actionId = row[ModerationQueueTable.actionId],
            editedText = row[ModerationQueueTable.editedText],
        )
    }
}
