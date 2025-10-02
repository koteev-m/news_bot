package routes

import analytics.AnalyticsPort
import io.ktor.client.request.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import referrals.ReferralCode
import referrals.ReferralsPort
import referrals.UTM

class RedirectRoutesTest {
    @Test
    fun `redirect returns deep link and tracks analytics`() = testApplication {
        val analytics = RecordingAnalytics()
        val referrals = RecordingReferrals()

        application {
            routing {
                redirectRoutes(analytics, referrals, "https://t.me/test_bot", 64)
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/go/news")
        assertEquals(302, response.status.value)
        val location = response.headers["Location"] ?: error("missing location")
        assertTrue(location.startsWith("https://t.me/test_bot?start="))

        val payload = extractStartPayload(location)
        assertTrue(payload.startsWith("id=news"))
        assertTrue(payload.toByteArray(StandardCharsets.UTF_8).size <= 64)

        val event = analytics.events.single()
        assertEquals("cta_click", event.type)
        assertEquals("news", event.props["id"])
    }

    @Test
    fun `redirect captures referral and utm params`() = testApplication {
        val analytics = RecordingAnalytics()
        val referrals = RecordingReferrals()

        application {
            routing {
                redirectRoutes(analytics, referrals, "https://t.me/test_bot", 64)
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/go/promo?ref=R7G5K2&utm_source=channel&utm_medium=cta&utm_campaign=october&utm_extra=${"x".repeat(100)}")
        assertEquals(302, response.status.value)
        val payload = extractStartPayload(response.headers["Location"]!!)
        assertTrue(payload.contains("ref=R7G5K2"))
        assertTrue(payload.toByteArray(StandardCharsets.UTF_8).size <= 64)

        val visit = referrals.visits.single()
        assertEquals("R7G5K2", visit.code)
        assertEquals("channel", visit.utm.source)
        assertEquals("cta", visit.utm.medium)
        assertEquals("october", visit.utm.campaign)

        val event = analytics.events.single { it.props["id"] == "promo" }
        assertEquals("", event.props["cta"])
    }

    private fun extractStartPayload(location: String): String {
        val uri = URI(location)
        val query = uri.query ?: return ""
        val startParam = query.split('&').firstOrNull { it.startsWith("start=") } ?: return ""
        val value = startParam.substringAfter('=')
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private class RecordingAnalytics : AnalyticsPort {
        data class Event(val type: String, val userId: Long?, val source: String?, val props: Map<String, String>)
        val events = mutableListOf<Event>()

        override suspend fun track(
            type: String,
            userId: Long?,
            source: String?,
            sessionId: String?,
            props: Map<String, Any?>,
            ts: java.time.Instant
        ) {
            val normalized = props.mapValues { it.value?.toString() ?: "" }
            events += Event(type, userId, source, normalized)
        }
    }

    private class RecordingReferrals : ReferralsPort {
        data class Visit(val code: String, val utm: UTM, val tgUserId: Long?)
        val visits = mutableListOf<Visit>()

        override suspend fun create(ownerUserId: Long, code: String): ReferralCode = ReferralCode(code, ownerUserId)

        override suspend fun find(code: String): ReferralCode? = null

        override suspend fun recordVisit(code: String, tgUserId: Long?, utm: UTM) {
            visits += Visit(code, utm, tgUserId)
        }

        override suspend fun attachUser(code: String, tgUserId: Long) {
            visits += Visit(code, UTM(null, null, null), tgUserId)
        }
    }
}
