package routes

import analytics.AnalyticsPort
import deeplink.DeepLinkPayload
import deeplink.DeepLinkStore
import deeplink.DeepLinkType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import referrals.ReferralsPort
import referrals.UTM
import security.userIdOrNull
import kotlin.time.Duration

fun Route.redirectRoutes(
    analytics: AnalyticsPort,
    referrals: ReferralsPort,
    botDeepLinkBase: String,
    deepLinkStore: DeepLinkStore,
    deepLinkTtl: Duration
) {
    get("/go/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        if (id.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val utm = UTM(
            source = call.request.queryParameters["utm_source"],
            medium = call.request.queryParameters["utm_medium"],
            campaign = call.request.queryParameters["utm_campaign"],
            ctaId = call.request.queryParameters["cta"]
        )
        val ref = call.request.queryParameters["ref"]?.takeIf { it.isNotBlank() }
        if (ref != null) {
            referrals.recordVisit(ref, null, utm)
        }
        analytics.track(
            type = "cta_click",
            userId = call.userIdOrNull?.toLongOrNull(),
            source = "redirect",
            props = mapOf(
                "id" to id,
                "utm_source" to (utm.source ?: ""),
                "utm_medium" to (utm.medium ?: ""),
                "utm_campaign" to (utm.campaign ?: ""),
                "ref" to (ref ?: ""),
                "cta" to (utm.ctaId ?: "")
            )
        )
        val payload = buildPayload(id = id, utm = utm, ref = ref)
        val shortCode = deepLinkStore.put(payload, deepLinkTtl)
        val sanitizedBase = botDeepLinkBase.trim().trimEnd('/', '?')
        val target = "$sanitizedBase?start=$shortCode"
        call.respondRedirect(target, permanent = false)
    }
}

private fun buildPayload(id: String, utm: UTM, ref: String?): DeepLinkPayload {
    return DeepLinkPayload(
        type = DeepLinkType.TOPIC,
        id = sanitizeToken(id),
        utmSource = sanitizeTokenOrNull(utm.source),
        utmMedium = sanitizeTokenOrNull(utm.medium),
        utmCampaign = sanitizeTokenOrNull(utm.campaign),
        ref = sanitizeTokenOrNull(ref),
    )
}

private fun sanitizeTokenOrNull(value: String?): String? {
    if (value.isNullOrBlank()) {
        return null
    }
    return sanitizeToken(value)
}

private fun sanitizeToken(value: String): String {
    val filtered = value.trim().take(64).map { ch ->
        when {
            ch.isLetterOrDigit() -> ch
            ch in listOf('-', '_', '.', ':') -> ch
            else -> '_'
        }
    }
    return filtered.joinToString(separator = "")
}
