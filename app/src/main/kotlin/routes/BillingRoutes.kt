package routes

import billing.model.Tier
import billing.service.BillingService
import billing.service.EntitlementsService
import billing.stars.BotBalanceRateLimiter
import billing.stars.BotStarBalancePort
import billing.stars.CacheState
import billing.stars.RateLimitVerdict
import billing.stars.StarsAdminResults
import billing.stars.StarsClient
import billing.stars.StarsClientBadRequest
import billing.stars.StarsClientDecodeError
import billing.stars.StarsClientRateLimited
import billing.stars.StarsClientServerError
import billing.stars.StarsHeaders
import billing.stars.StarsMetrics
import billing.stars.StarsOutcomes
import billing.stars.StarsPublicResults
import billing.stars.StarBalancePort
import billing.stars.StarAmount
import billing.stars.label
import io.ktor.http.HttpHeaders
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
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (err: Error) {
                throw err
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
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val registry = call.meterRegistry()
            val sample = registry?.let { Timer.start(it) }
            try {
                fun record(result: String) {
                    registry?.counter(StarsMetrics.CNT_PUBLIC_REQUESTS, StarsMetrics.LABEL_RESULT, result)?.increment()
                }

                fun recordOutcome(outcome: String) {
                    registry?.counter(StarsMetrics.CNT_OUTCOME, StarsMetrics.LABEL_OUTCOME, outcome)?.increment()
                }

                val starsClient = call.starsClient()
                    ?: run {
                        record(StarsPublicResults.UNCONFIGURED)
                        return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "telegram not configured"),
                        )
                    }

                val result = runCatching { starsClient.getBotStarAmount() }
                result.fold(
                    onSuccess = { amount: StarAmount ->
                        record(StarsPublicResults.OK)
                        recordOutcome(StarsOutcomes.SUCCESS)
                        call.respond(HttpStatusCode.OK, amount)
                    },
                    onFailure = { error ->
                        when (error) {
                            is StarsClientRateLimited -> {
                                record(StarsPublicResults.TG_RATE_LIMITED)
                                recordOutcome(StarsOutcomes.RATE_LIMITED)
                                error.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
                                call.respond(
                                    HttpStatusCode.TooManyRequests,
                                    mapOf("error" to "telegram rate limited"),
                                )
                            }

                            is StarsClientServerError -> {
                                record(StarsPublicResults.SERVER)
                                recordOutcome(StarsOutcomes.SERVER)
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "telegram unavailable"),
                                )
                            }

                            is StarsClientBadRequest -> {
                                record(StarsPublicResults.BAD_REQUEST)
                                recordOutcome(StarsOutcomes.BAD_REQUEST)
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    mapOf("error" to "telegram bad response"),
                                )
                            }

                            is StarsClientDecodeError -> {
                                record(StarsPublicResults.DECODE_ERROR)
                                recordOutcome(StarsOutcomes.DECODE_ERROR)
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    mapOf("error" to "telegram bad response"),
                                )
                            }

                            else -> {
                                record(StarsPublicResults.OTHER)
                                recordOutcome(StarsOutcomes.OTHER)
                                call.application.environment.log.error("billing.getMyStarBalance failure", error)
                                call.respondInternal()
                            }
                        }
                    },
                )
            } finally {
                registry?.let { reg ->
                    sample?.stop(
                        Timer
                            .builder(StarsMetrics.TIMER_PUBLIC)
                            .register(reg),
                    )
                }
            }
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

    route("/api/admin/stars") {
        get("/bot-balance") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val registry = call.meterRegistry()
            val sample = registry?.let { Timer.start(it) }
            try {
                val subject = call.userIdOrNull?.toLongOrNull()
                    ?: run {
                        registry?.counter(
                            StarsMetrics.CNT_ADMIN_REQUESTS,
                            StarsMetrics.LABEL_RESULT,
                            StarsAdminResults.UNAUTHORIZED,
                        )?.increment()
                        return@get call.respondUnauthorized()
                    }

                val adminUserIds = call.adminUserIds()
                if (subject !in adminUserIds) {
                    registry?.counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.FORBIDDEN,
                    )?.increment()
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                    return@get
                }

                val service = call.botBalanceServiceOrNull()
                    ?: run {
                        registry?.counter(
                            StarsMetrics.CNT_ADMIN_REQUESTS,
                            StarsMetrics.LABEL_RESULT,
                            StarsAdminResults.UNCONFIGURED,
                        )?.increment()
                        return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "telegram not configured"),
                        )
                    }

                when (val verdict = call.botBalanceRateLimiter()?.check(subject)) {
                    is RateLimitVerdict.Denied -> {
                        call.response.headers.append(HttpHeaders.RetryAfter, verdict.retryAfterSeconds.toString())
                        registry?.counter(
                            StarsMetrics.CNT_ADMIN_REQUESTS,
                            StarsMetrics.LABEL_RESULT,
                            StarsAdminResults.LOCAL_RATE_LIMITED,
                        )?.increment()
                        call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate limited"))
                        return@get
                    }

                    else -> Unit
                }
                val result = runCatching { service.getBotStarBalance() }
                result.fold(
                    onSuccess = { balanceResult ->
                        registry?.counter(
                            StarsMetrics.CNT_ADMIN_REQUESTS,
                            StarsMetrics.LABEL_RESULT,
                            StarsAdminResults.OK,
                        )?.increment()
                        call.response.headers.append(StarsHeaders.CACHE, balanceResult.cacheState.label())
                        balanceResult.cacheAgeSeconds?.let { age ->
                            val safeAge = age.coerceAtLeast(0)
                            call.response.headers.append(StarsHeaders.CACHE_AGE, safeAge.toString())
                        }
                        call.respond(HttpStatusCode.OK, balanceResult.balance)
                    },
                    onFailure = { error ->
                        when (error) {
                            is StarsClientRateLimited -> {
                                registry?.counter(
                                    StarsMetrics.CNT_ADMIN_REQUESTS,
                                    StarsMetrics.LABEL_RESULT,
                                    StarsAdminResults.TG_RATE_LIMITED,
                                )?.increment()
                                if (error.retryAfterSeconds != null) {
                                    call.response.headers.append(HttpHeaders.RetryAfter, error.retryAfterSeconds.toString())
                                }
                                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "telegram rate limited"))
                            }

                            is StarsClientServerError -> {
                                registry?.counter(
                                    StarsMetrics.CNT_ADMIN_REQUESTS,
                                    StarsMetrics.LABEL_RESULT,
                                    StarsAdminResults.SERVER,
                                )?.increment()
                                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "telegram unavailable"))
                            }

                            is StarsClientBadRequest -> {
                                registry?.counter(
                                    StarsMetrics.CNT_ADMIN_REQUESTS,
                                    StarsMetrics.LABEL_RESULT,
                                    StarsAdminResults.BAD_REQUEST,
                                )?.increment()
                                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "telegram bad request"))
                            }

                            is StarsClientDecodeError -> {
                                registry?.counter(
                                    StarsMetrics.CNT_ADMIN_REQUESTS,
                                    StarsMetrics.LABEL_RESULT,
                                    StarsAdminResults.DECODE_ERROR,
                                )?.increment()
                                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "telegram decode error"))
                            }

                            else -> {
                                registry?.counter(
                                    StarsMetrics.CNT_ADMIN_REQUESTS,
                                    StarsMetrics.LABEL_RESULT,
                                    StarsAdminResults.OTHER,
                                )?.increment()
                                call.application.environment.log.error("billing.getBotStarBalance failure", error)
                                call.respondInternal()
                            }
                        }
                    },
                )
            } finally {
                registry?.let { reg ->
                    sample?.stop(
                        Timer
                            .builder(StarsMetrics.TIMER_ADMIN)
                            .register(reg),
                    )
                }
            }
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
    val botStarBalancePort: BotStarBalancePort? = null,
    val entitlementsService: EntitlementsService? = null,
    val adminUserIds: Set<Long> = emptySet(),
    val botBalanceRateLimiter: BotBalanceRateLimiter? = null,
    val meterRegistry: MeterRegistry? = null,
    val starsClient: StarsClient? = null,
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

private fun ApplicationCall.botBalanceServiceOrNull(): BotStarBalancePort? {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].botStarBalancePort
    }
    return null
}

private fun ApplicationCall.adminUserIds(): Set<Long> {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].adminUserIds
    }
    error("AdminUserIds are not configured")
}

private fun ApplicationCall.botBalanceRateLimiter(): BotBalanceRateLimiter? {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].botBalanceRateLimiter
    }
    return null
}

private fun ApplicationCall.starsClient(): StarsClient? {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].starsClient
    }
    return null
}

private fun ApplicationCall.meterRegistry(): MeterRegistry? {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].meterRegistry
    }
    return null
}

private fun ApplicationCall.entitlementsService(): EntitlementsService {
    val attributes = application.attributes
    if (attributes.contains(BillingRouteServicesKey)) {
        return attributes[BillingRouteServicesKey].entitlementsService
            ?: error("EntitlementsService is not configured")
    }
    error("EntitlementsService is not configured")
}
