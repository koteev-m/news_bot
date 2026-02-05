package tenancy

interface QuotaRepo {
    suspend fun getQuotas(tenantId: Long): Quotas

    suspend fun countPortfolios(tenantId: Long): Int

    suspend fun countAlerts(tenantId: Long): Int
}

class QuotaService(
    private val repo: QuotaRepo,
) {
    suspend fun ensurePortfolioQuota(ctx: TenantContext) {
        val quotas = repo.getQuotas(ctx.tenant.tenantId)
        val used = repo.countPortfolios(ctx.tenant.tenantId)
        if (used >= quotas.maxPortfolios) throw QuotaExceeded("max portfolios reached")
    }

    suspend fun ensureAlertsQuota(ctx: TenantContext) {
        val quotas = repo.getQuotas(ctx.tenant.tenantId)
        val used = repo.countAlerts(ctx.tenant.tenantId)
        if (used >= quotas.maxAlerts) throw QuotaExceeded("max alerts reached")
    }
}

class QuotaExceeded(
    message: String,
) : RuntimeException(message)
