package routes

import ab.ExperimentsService
import analytics.AnalyticsPort
import common.runCatchingNonFatal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import observability.EventsCounter
import pricing.PricingService
import repo.PaywallCopy
import repo.PriceOverride
import repo.PricingRepository
import security.userIdOrNull

@Serializable
data class OverrideUpsertRequest(
    val key: String,
    val variant: String,
    val tier: String,
    val priceXtr: Long,
    val starsPackage: Long? = null,
)

@Serializable
data class CopyUpsertRequest(
    val key: String,
    val variant: String,
    val headingEn: String,
    val subEn: String,
    val ctaEn: String,
)

@Serializable
data class CtaClickRequest(
    val plan: String,
    val variant: String,
)

fun Route.pricingRoutes(
    experiments: ExperimentsService,
    pricing: PricingService,
    analytics: AnalyticsPort,
    eventsCounter: EventsCounter,
) {
    get("/api/pricing/offers") {
        val userId = call.userIdOrNull?.toLongOrNull()
        val subject = userId ?: 0L
        val copyVariant = assignVariant(experiments, subject, "paywall_copy", fallback = "A")
        val priceVariant = assignVariant(experiments, subject, "price_bundle", fallback = "A")
        val payload = pricing.buildPaywall(userId, copyVariant, priceVariant)
        analytics.track(
            type = "paywall_view",
            userId = userId,
            source = "api",
            props =
            mapOf(
                "copy_variant" to copyVariant,
                "price_variant" to priceVariant,
            ),
        )
        eventsCounter.inc("paywall_view")
        call.respond(HttpStatusCode.OK, payload)
    }

    post("/api/pricing/cta") {
        val userId = call.userIdOrNull?.toLongOrNull()
        val body = call.receive<CtaClickRequest>()
        analytics.track(
            type = "paywall_cta_click",
            userId = userId,
            source = "api",
            props =
            mapOf(
                "plan" to body.plan,
                "variant" to body.variant,
            ),
        )
        eventsCounter.inc("paywall_cta_click")
        call.respond(HttpStatusCode.Accepted)
    }
}

fun Route.adminPricingRoutes(
    repository: PricingRepository,
    adminUserIds: Set<Long>,
) {
    route("/api/admin/pricing") {
        post("/override") {
            val userId = call.userIdOrNull?.toLongOrNull() ?: return@post call.respondUnauthorized()
            if (userId !in adminUserIds) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@post
            }
            val request = call.receive<OverrideUpsertRequest>()
            if (request.key != PRICE_BUNDLE_KEY) {
                call.respondBadRequest(listOf("key must be $PRICE_BUNDLE_KEY"))
                return@post
            }
            val normalizedVariant = request.variant.trim().uppercase()
            if (normalizedVariant !in PRICE_VARIANTS) {
                call.respondBadRequest(listOf("variant must be one of ${PRICE_VARIANTS.joinToString()}"))
                return@post
            }
            val normalizedTier = request.tier.trim().uppercase()
            if (normalizedTier !in ALLOWED_TIERS) {
                call.respondBadRequest(listOf("tier must be one of ${ALLOWED_TIERS.joinToString()}"))
                return@post
            }
            if (request.priceXtr < 0) {
                call.respondBadRequest(listOf("priceXtr must be non-negative"))
                return@post
            }
            val override =
                PriceOverride(
                    key = request.key,
                    variant = normalizedVariant,
                    tier = normalizedTier,
                    priceXtr = request.priceXtr,
                    starsPackage = request.starsPackage,
                )
            repository.upsertOverride(override)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/copy") {
            val userId = call.userIdOrNull?.toLongOrNull() ?: return@post call.respondUnauthorized()
            if (userId !in adminUserIds) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@post
            }
            val request = call.receive<CopyUpsertRequest>()
            if (request.key != PAYWALL_COPY_KEY) {
                call.respondBadRequest(listOf("key must be $PAYWALL_COPY_KEY"))
                return@post
            }
            val normalizedVariant = request.variant.trim().uppercase()
            if (normalizedVariant !in COPY_VARIANTS) {
                call.respondBadRequest(listOf("variant must be one of ${COPY_VARIANTS.joinToString()}"))
                return@post
            }
            val copy =
                PaywallCopy(
                    key = request.key,
                    variant = normalizedVariant,
                    headingEn = request.headingEn.trim(),
                    subEn = request.subEn.trim(),
                    ctaEn = request.ctaEn.trim(),
                )
            repository.upsertCopy(copy)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun assignVariant(
    experiments: ExperimentsService,
    userId: Long,
    key: String,
    fallback: String,
): String = runCatchingNonFatal { experiments.assign(userId, key).variant }.getOrDefault(fallback)

private const val PRICE_BUNDLE_KEY = "price_bundle"
private const val PAYWALL_COPY_KEY = "paywall_copy"
private val PRICE_VARIANTS = setOf("A", "B", "C")
private val COPY_VARIANTS = setOf("A", "B")
private val ALLOWED_TIERS = setOf("FREE", "PRO", "PRO_PLUS", "VIP")
