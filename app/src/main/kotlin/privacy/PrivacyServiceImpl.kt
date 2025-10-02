package privacy

import db.DatabaseFactory.dbQuery
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.sql.transactions.TransactionManager
import repo.PrivacyRepository

class PrivacyServiceImpl(
    private val repo: PrivacyRepository,
    private val cfg: PrivacyConfig
) : PrivacyService {

    override suspend fun enqueueErasure(userId: Long) {
        ensureErasureEnabled()
        repo.upsertRequest(userId)
    }

    override suspend fun runErasure(userId: Long, dryRun: Boolean): ErasureReport {
        ensureErasureEnabled()
        val effectiveDryRun = dryRun || cfg.erasure.dryRun
        val deleted = linkedMapOf<String, Long>()
        val anonymized = linkedMapOf<String, Long>()

        try {
            val eventsCount = deleteByUser("events", "user_id", userId, effectiveDryRun)
            recordOutcome(userId, "events", eventsCount, deleted, anonymized, effectiveDryRun, anonymize = false)

            val overridesCount = deleteByUser("user_alert_overrides", "user_id", userId, effectiveDryRun)
            recordOutcome(userId, "user_alert_overrides", overridesCount, deleted, anonymized, effectiveDryRun, false)

            val alertsEventsCount = deleteAlertEvents(userId, effectiveDryRun)
            recordOutcome(userId, "alerts_events", alertsEventsCount, deleted, anonymized, effectiveDryRun, false)

            val alertsRulesCount = deleteByUser("alerts_rules", "user_id", userId, effectiveDryRun)
            recordOutcome(userId, "alerts_rules", alertsRulesCount, deleted, anonymized, effectiveDryRun, false)

            val subscriptionsCount = deleteByUser("user_subscriptions", "user_id", userId, effectiveDryRun)
            recordOutcome(userId, "user_subscriptions", subscriptionsCount, deleted, anonymized, effectiveDryRun, false)

            val portfoliosCount = deleteByUser("portfolios", "user_id", userId, effectiveDryRun)
            recordOutcome(userId, "portfolios", portfoliosCount, deleted, anonymized, effectiveDryRun, false)

            val botStartsCount = anonymizeUserId("bot_starts", "user_id", userId, effectiveDryRun)
            recordOutcome(userId, "bot_starts", botStartsCount, deleted, anonymized, effectiveDryRun, anonymize = true)

            val usersCount = deleteByUser("users", "user_id", userId, effectiveDryRun)
            recordOutcome(userId, "users", usersCount, deleted, anonymized, effectiveDryRun, false)

            val finalStatus = if (effectiveDryRun) "DRYRUN" else "DONE"
            repo.markResult(userId, finalStatus, null)
        } catch (ex: Throwable) {
            repo.markResult(userId, "ERROR", ex::class.simpleName ?: "UnknownError")
            throw ex
        }

        return ErasureReport(userId, deleted, anonymized, effectiveDryRun)
    }

    override suspend fun runRetention(now: Instant): RetentionReport {
        return try {
            val deletedByTable = linkedMapOf<String, Long>()

            val analyticsCutoff = now.minus(cfg.retention.analyticsDays.toLong(), ChronoUnit.DAYS)
            val analyticsRemoved = deleteOlderThan("events", "ts", analyticsCutoff)
            if (analyticsRemoved > 0) {
                deletedByTable["events"] = analyticsRemoved
            }

            val alertsCutoff = now.minus(cfg.retention.alertsDays.toLong(), ChronoUnit.DAYS)
            val alertsRemoved = deleteOlderThan("alerts_events", "ts", alertsCutoff)
            if (alertsRemoved > 0) {
                deletedByTable["alerts_events"] = alertsRemoved
            }

            val botCutoff = now.minus(cfg.retention.botStartsDays.toLong(), ChronoUnit.DAYS)
            val botAnon = anonymizeOlderThan("bot_starts", "started_at", "user_id", botCutoff)
            if (botAnon > 0) {
                deletedByTable["bot_starts.anonymized"] = botAnon
            }

            RetentionReport(deletedByTable)
        } catch (ex: IllegalStateException) {
            if (ex.message?.contains("Database.connect") == true) {
                RetentionReport(emptyMap())
            } else {
                throw ex
            }
        }
    }

    private fun ensureErasureEnabled() {
        if (!cfg.erasure.enabled) {
            error("Privacy erasure is disabled")
        }
    }

    private suspend fun recordOutcome(
        userId: Long,
        table: String,
        count: Long,
        deleted: MutableMap<String, Long>,
        anonymized: MutableMap<String, Long>,
        dryRun: Boolean,
        anonymize: Boolean
    ) {
        val logAction = when {
            dryRun -> "SKIP"
            count == 0L -> "SKIP"
            anonymize -> "ANONYMIZE"
            else -> "DELETE"
        }
        repo.audit(userId, table, logAction, count)

        if (count > 0 && !dryRun) {
            if (anonymize) {
                anonymized[table] = count
            } else {
                deleted[table] = count
            }
        }
    }

    private suspend fun deleteByUser(table: String, column: String, userId: Long, dryRun: Boolean): Long {
        val count = countByUser(table, column, userId)
        if (!dryRun && count > 0) {
            exec("DELETE FROM $table WHERE $column = $userId")
        }
        return count
    }

    private suspend fun deleteAlertEvents(userId: Long, dryRun: Boolean): Long {
        val countSql = "SELECT count(*) FROM alerts_events WHERE rule_id IN (SELECT rule_id FROM alerts_rules WHERE user_id = $userId)"
        val count = countWithQuery(countSql)
        if (!dryRun && count > 0) {
            exec("DELETE FROM alerts_events WHERE rule_id IN (SELECT rule_id FROM alerts_rules WHERE user_id = $userId)")
        }
        return count
    }

    private suspend fun anonymizeUserId(table: String, column: String, userId: Long, dryRun: Boolean): Long {
        val count = countByUser(table, column, userId)
        if (!dryRun && count > 0) {
            exec("UPDATE $table SET $column = NULL WHERE $column = $userId")
        }
        return count
    }

    private suspend fun deleteOlderThan(table: String, tsColumn: String, cutoff: Instant): Long {
        val cutoffLiteral = "TIMESTAMPTZ '${Timestamp.from(cutoff).toInstant()}'"
        val count = countWithQuery("SELECT count(*) FROM $table WHERE $tsColumn < $cutoffLiteral")
        if (count > 0) {
            exec("DELETE FROM $table WHERE $tsColumn < $cutoffLiteral")
        }
        return count
    }

    private suspend fun anonymizeOlderThan(
        table: String,
        tsColumn: String,
        userColumn: String,
        cutoff: Instant
    ): Long {
        val cutoffLiteral = "TIMESTAMPTZ '${Timestamp.from(cutoff).toInstant()}'"
        val count = countWithQuery("SELECT count(*) FROM $table WHERE $tsColumn < $cutoffLiteral AND $userColumn IS NOT NULL")
        if (count > 0) {
            exec("UPDATE $table SET $userColumn = NULL WHERE $tsColumn < $cutoffLiteral AND $userColumn IS NOT NULL")
        }
        return count
    }

    private suspend fun countByUser(table: String, column: String, userId: Long): Long =
        countWithQuery("SELECT count(*) FROM $table WHERE $column = $userId")

    private suspend fun exec(sql: String) =
        dbQuery {
            TransactionManager.current().exec(sql)
        }

    private suspend fun countWithQuery(sql: String): Long =
        dbQuery {
            var result = 0L
            TransactionManager.current().exec(sql) { rs ->
                if (rs.next()) {
                    result = rs.getLong(1)
                }
            }
            result
        }
}

data class PrivacyConfig(
    val retention: RetentionCfg,
    val erasure: ErasureCfg
)

data class RetentionCfg(
    val analyticsDays: Int,
    val alertsDays: Int,
    val botStartsDays: Int
)

data class ErasureCfg(
    val enabled: Boolean,
    val dryRun: Boolean,
    val batchSize: Int
)
