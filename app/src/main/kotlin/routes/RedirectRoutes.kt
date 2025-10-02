package routes

import analytics.AnalyticsPort
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import referrals.ReferralsPort
import referrals.UTM
import kotlin.text.Charsets
import security.userIdOrNull

fun Route.redirectRoutes(
    analytics: AnalyticsPort,
    referrals: ReferralsPort,
    botDeepLinkBase: String,
    maxPayloadBytes: Int
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
        val payload = buildPayload(id = id, utm = utm, ref = ref, maxBytes = maxPayloadBytes)
        val sanitizedBase = botDeepLinkBase.trim().trimEnd('/', '?')
        val encodedPayload = payload.encodeURLParameter()
        val target = "$sanitizedBase?start=$encodedPayload"
        call.respondRedirect(target, permanent = false)
    }
}

private fun buildPayload(id: String, utm: UTM, ref: String?, maxBytes: Int): String {
    val parts = mutableListOf("id=${sanitizeToken(id)}")
    utm.source?.let { parts += "src=${sanitizeToken(it)}" }
    utm.medium?.let { parts += "med=${sanitizeToken(it)}" }
    utm.campaign?.let { parts += "cmp=${sanitizeToken(it)}" }
    utm.ctaId?.let { parts += "cta=${sanitizeToken(it)}" }
    ref?.let { parts += "ref=${sanitizeToken(it)}" }
    val raw = parts.joinToString(separator = "|")
    return trimToBytes(raw, maxBytes)
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

private fun trimToBytes(value: String, maxBytes: Int): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    var bytes = 0
    val builder = StringBuilder()
    for (ch in value) {
        val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
        if (bytes + charBytes > maxBytes) {
            break
        }
        builder.append(ch)
        bytes += charBytes
    }
    return builder.toString()
}
