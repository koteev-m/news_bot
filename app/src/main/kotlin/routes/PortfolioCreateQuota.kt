package routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import tenancy.QuotaExceeded
import tenancy.QuotaService
import tenancy.TenantContext

suspend fun ApplicationCall.ensurePortfolioQuota(quotaService: QuotaService, ctx: TenantContext) {
    try {
        quotaService.ensurePortfolioQuota(ctx)
    } catch (e: QuotaExceeded) {
        respond(HttpStatusCode.TooManyRequests, mapOf("code" to "QUOTA_EXCEEDED", "message" to "Portfolio quota exceeded"))
        throw e
    }
}
