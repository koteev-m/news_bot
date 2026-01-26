package routes

import analytics.AnalyticsPort
import deeplink.DeepLinkPayload
import deeplink.InMemoryDeepLinkStore
import io.ktor.client.request.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import referrals.ReferralCode
import referrals.ReferralsPort
import referrals.UTM

class RedirectRoutesTest {
    @Test
    fun `redirect returns deep link and tracks analytics`() = testApplication {
        val analytics = RecordingAnalytics()
        val referrals = RecordingReferrals()
        val store = InMemoryDeepLinkStore()

        application {
            routing {
                redirectRoutes(analytics, referrals, "https://t.me/test_bot", store, 14.days)
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/go/news")
        assertEquals(302, response.status.value)
        val location = response.headers["Location"] ?: error("missing location")
        assertTrue(location.startsWith("https://t.me/test_bot?start="))

        val payload = payloadFrom(store, location)
        assertEquals("news", payload?.id)

        val event = analytics.events.single()
        assertEquals("cta_click", event.type)
        assertEquals("news", event.props["id"])
    }

    @Test
    fun `redirect captures referral and utm params`() = testApplication {
        val analytics = RecordingAnalytics()
        val referrals = RecordingReferrals()
        val store = InMemoryDeepLinkStore()

        application {
            routing {
                redirectRoutes(analytics, referrals, "https://t.me/test_bot", store, 14.days)
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get(
            "/go/promo?ref=R7G5K2&utm_source=channel&utm_medium=cta&utm_campaign=october&utm_extra=${"x".repeat(100)}"
        )
        assertEquals(302, response.status.value)
        val payload = payloadFrom(store, response.headers["Location"]!!)
        assertEquals("R7G5K2", payload?.ref)

        val visit = referrals.visits.single()
        assertEquals("R7G5K2", visit.code)
        assertEquals("channel", visit.utm.source)
        assertEquals("cta", visit.utm.medium)
        assertEquals("october", visit.utm.campaign)

        val event = analytics.events.single { it.props["id"] == "promo" }
        assertEquals("", event.props["cta"])
    }

    private fun payloadFrom(store: InMemoryDeepLinkStore, location: String): DeepLinkPayload? {
        val uri = URI(location)
        val query = uri.query ?: return null
        val startParam = query.split('&').firstOrNull { it.startsWith("start=") } ?: return null
        val value = startParam.substringAfter('=')
        return store.get(value)
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
