package routes

import ab.Assignment
import ab.ExperimentsService
import analytics.AnalyticsPort
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import observability.EventsCounter
import pricing.Offer
import pricing.PricingPort
import pricing.PricingService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakePricingPort : PricingPort {
    override suspend fun getBasePlans(): Map<String, Long> = mapOf("PRO" to 1000L, "PRO_PLUS" to 2000L, "VIP" to 5000L)

    override suspend fun listOverrides(
        key: String,
        variant: String,
    ): List<Offer> = if (key == "price_bundle" && variant == "B") listOf(Offer("PRO", 900)) else emptyList()

    override suspend fun getCopy(
        key: String,
        variant: String,
    ): Triple<String, String, String>? = if (key == "paywall_copy" && variant == "A") Triple("H", "S", "CTA") else null
}

private class FakeExperiments : ExperimentsService {
    override suspend fun assign(
        userId: Long,
        key: String,
    ): Assignment = Assignment(userId, key, if (key == "price_bundle") "B" else "A")

    override suspend fun activeAssignments(userId: Long): List<Assignment> = emptyList()
}

private class FakeAnalytics : AnalyticsPort {
    override suspend fun track(
        type: String,
        userId: Long?,
        source: String?,
        sessionId: String?,
        props: Map<String, Any?>,
        ts: java.time.Instant,
    ) {
        // no-op
    }
}

class PricingRoutesTest {
    @Test
    fun `should return offers with overrides`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    val registry = SimpleMeterRegistry()
                    val eventsCounter = EventsCounter(registry)
                    pricingRoutes(FakeExperiments(), PricingService(FakePricingPort()), FakeAnalytics(), eventsCounter)
                }
            }

            val response = client.get("/api/pricing/offers")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"offers\""))
            assertTrue(body.contains("900"))
        }
}
