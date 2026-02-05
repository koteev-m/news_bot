package observability

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import observability.funnel.FunnelMetrics
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsEndpointFunnelTest {
    @Test
    fun `metrics endpoint exposes funnel and slo metrics`() =
        testApplication {
            application {
                val registry = Observability.install(this)
                val funnel = FunnelMetrics(registry)
                funnel.recordPostView()
                funnel.recordCtaClick()
                funnel.recordBotStart()
                funnel.recordBreakingLatency(Instant.now(), Instant.now().minusSeconds(90))
            }

            val body = client.get("/metrics").bodyAsText()
            assertTrue(Regex("(?m)^post_views_total(\\{|\\s)").containsMatchIn(body))
            assertTrue(Regex("(?m)^cta_click_total(\\{|\\s)").containsMatchIn(body))
            assertTrue(Regex("(?m)^bot_start_total(\\{|\\s)").containsMatchIn(body))
            assertFalse(body.contains("post_views_total_total"))
            assertFalse(body.contains("cta_click_total_total"))
            assertFalse(body.contains("bot_start_total_total"))
            assertTrue(body.contains("breaking_publish_latency_seconds_bucket"))
        }
}
