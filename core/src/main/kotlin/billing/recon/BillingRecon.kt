package billing.recon

data class LedgerEntry(
    val userId: Long,
    val tier: String,
    val event: String,
    val providerPaymentId: String,
    val payloadHash: String,
)

data class ReconResult(
    val runId: Long,
    val status: String,
    val counts: Map<String, Int>,
)

interface BillingLedgerPort {
    suspend fun append(entry: LedgerEntry)
}

interface BillingReconPort {
    suspend fun beginRun(): Long

    suspend fun recordMismatch(
        runId: Long,
        kind: String,
        userId: Long?,
        providerPaymentId: String?,
        tier: String?,
    )

    suspend fun finishRun(
        runId: Long,
        status: String,
        notes: String?,
    )
}
