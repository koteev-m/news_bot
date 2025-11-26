package routes

import billing.model.Tier
import billing.service.BillingService
import billing.service.EntitlementsService
import billing.stars.StarBalancePort
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import routes.dto.EntitlementDto
import routes.dto.UserSubscriptionDto
import routes.dto.toDto
import security.userIdOrNull

fun Route.billingRoutes() {
    route("/api/billing") {
        get("/plans") {
            val svc = call.billingService()
            val result = svc.listPlans()
            result.fold(
                onSuccess = { plans ->
                    val payload = plans.map { plan -> plan.toDto() }
                    call.respond(HttpStatusCode.OK, payload)
                },
                onFailure = { error ->
                    call.application.environment.log.error("billing.listPlans failure", error)
                    call.respondInternal()
                }
            )
        }
    }

    route("/api/billing/stars") {
        post("/invoice") {
            val subject = call.userIdOrNull?.toLongOrNull()
                ?: return@post call.respondUnauthorized()

            val svc = call.billingService()
            val request = try {
                call.receive<CreateInvoiceRequest>()
            } catch (_: Throwable) {
                call.respondBadRequest(listOf("body invalid"))
                return@post
            }

            val tier = runCatching { Tier.parse(request.tier) }.getOrElse {
                call.respondBadRequest(listOf("tier invalid"))
                return@post
            }

            val result = svc.createInvoiceFor(subject, tier)
            result.fold(
                onSuccess = { link ->
                    call.respond(HttpStatusCode.Created, InvoiceLinkResponse(link))
                },
                onFailure = { error ->
                    when (error) {
                        is NoSuchElementException -> call.respondBadRequest(listOf("plan not found or inactive"))
                        is IllegalArgumentException -> call.respondBadRequest(listOf(error.message ?: "bad request"))
                        else -> {
                            call.application.environment.log.error("billing.createInvoice failure", error)
                            call.respondInternal()
                        }
                    }
                }
            )
        }

        get("/balance") {
            val subject = call.userIdOrNull?.toLongOrNull()
                ?: return@get call.respondUnauthorized()

            val starPort = call.balanceService()
            val result = runCatching { starPort.getMyStarBalance(subject) }
            result.fold(
                onSuccess = { balance ->
                    call.respond(HttpStatusCode.OK, balance)
                },
                onFailure = { error ->
                    call.application.environment.log.error("billing.getMyStarBalance failure", error)
                    call.respondInternal()
                },
            )
        }

        get("/me") {
            val subject = call.userIdOrNull?.toLongOrNull()
                ?: return@get call.respondUnauthorized()

            val svc = call.billingService()
            val result = svc.getMySubscription(subject)
            result.fold(
                onSuccess = { subscription ->
                    if (subscription == null) {
                        call.respond(HttpStatusCode.OK, MySubscriptionResponse.free())
                    } else {
                        val dto = subscription.toDto()
                        call.respond(HttpStatusCode.OK, MySubscriptionResponse.from(dto))
                    }
                },
                onFailure = { error ->
                    call.application.environment.log.error("billing.getMySubscription failure", error)
                    call.respondInternal()
                }
            )
        }
    }

    route("/api/billing") {
        get("/entitlements") {
            val subject = call.userIdOrNull?.toLongOrNull()
                ?: return@get call.respondUnauthorized()

            val svc = call.entitlementsService()
            val result = svc.getEntitlement(subject)
            result.fold(
                onSuccess = { entitlement ->
                    val dto: EntitlementDto = entitlement.toDto()
                    call.respond(HttpStatusCode.OK, dto)
                },
                onFailure = { error ->
                    call.application.environment.log.error("billing.getEntitlement failure", error)
                    call.respondInternal()
                },
            )
        }
    }
}

@Serializable
private data class CreateInvoiceRequest(val tier: String)

@Serializable
private data class InvoiceLinkResponse(val invoiceLink: String)

@Serializable
private data class MySubscriptionResponse(
    val tier: String,
    val status: String,
    val startedAt: String?,
    val expiresAt: String?
) {
    companion object {
        fun from(dto: UserSubscriptionDto): MySubscriptionResponse = MySubscriptionResponse(
            tier = dto.tier,
            status = dto.status,
            startedAt = dto.startedAt,
            expiresAt = dto.expiresAt
        )

        fun free(): MySubscriptionResponse = MySubscriptionResponse(
            tier = Tier.FREE.name,
            status = "NONE",
            startedAt = null,
            expiresAt = null
        )
    }
}

internal val BillingRouteServicesKey: AttributeKey<BillingRouteServices> =
    AttributeKey("BillingRouteServices")

internal data class BillingRouteServices(
    val billingService: BillingService,
    val starBalancePort: StarBalancePort? = null,
    val entitlementsService: EntitlementsService? = null,
)

private fun ApplicationCall.billingService(): BillingService {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].billingService
    }
    error("BillingService is not configured")
}

private fun ApplicationCall.balanceService(): StarBalancePort {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].starBalancePort
            ?: error("StarBalancePort is not configured")
    }
    error("StarBalancePort is not configured")
}

private fun ApplicationCall.entitlementsService(): EntitlementsService {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].entitlementsService
            ?: error("EntitlementsService is not configured")
    }
    error("EntitlementsService is not configured")
}
