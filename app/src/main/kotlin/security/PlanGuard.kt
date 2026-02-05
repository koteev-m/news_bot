package security

import billing.model.Tier
import billing.service.BillingService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import routes.respondInternal

suspend fun ApplicationCall.requireTierAtLeast(
    required: Tier,
    svc: BillingService,
    onDenied: suspend () -> Unit = { respondForbiddenTier(required.name) },
): Boolean {
    val subject = userIdOrNull?.toLongOrNull() ?: return false
    val subscription = svc.getMySubscription(subject)
    val current =
        subscription.getOrElse { error ->
            application.environment.log.error("plan_guard.subscription_error", error)
            respondInternal()
            return false
        }
    val allowed = current?.tier?.level()?.let { it >= required.level() } ?: false
    if (!allowed) {
        onDenied()
    }
    return allowed
}

suspend fun ApplicationCall.respondForbiddenTier(required: String) {
    respond(
        HttpStatusCode.Forbidden,
        mapOf(
            "error" to "forbidden",
            "reason" to "tier_required",
            "required" to required,
        ),
    )
}
