package billing.recon

import db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.transactions.TransactionManager

class BillingReconJob(
    private val recon: BillingReconPort,
) {
    suspend fun run(): ReconResult {
        val runId = recon.beginRun()
        var mismatches = 0

        // 1) Найти ledger APPLY без активной подписки (возможен сбой апдейта)
        // (Псевдо: проверить в user_subscriptions ACTIVE для user_id/tier)
        // 2) Найти дубликаты ledger APPLY для одного provider_payment_id
        // Здесь добавим простой пример «дубликаты по pid+event=APPLY»
        val duplicatePaymentIds =
            dbQuery {
                val ids = mutableListOf<String>()
                TransactionManager.current().exec(
                    """
                    SELECT provider_payment_id
                    FROM billing_ledger
                    WHERE event = 'APPLY'
                    GROUP BY provider_payment_id
                    HAVING COUNT(*) > 1
                    """.trimIndent(),
                ) { rs ->
                    while (rs.next()) {
                        ids += rs.getString(1)
                    }
                }
                ids
            }
        for (pid in duplicatePaymentIds) {
            recon.recordMismatch(runId, "DUPLICATE", null, pid, null)
        }
        val dup = duplicatePaymentIds.size
        mismatches += dup

        val status = if (mismatches == 0) "OK" else "WARN"
        recon.finishRun(runId, status, if (mismatches == 0) null else "$mismatches mismatches")
        return ReconResult(runId, status, mapOf("duplicates" to dup))
    }
}
