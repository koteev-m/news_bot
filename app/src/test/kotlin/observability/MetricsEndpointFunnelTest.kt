package observability

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import observability.funnel.FunnelMetrics

class MetricsEndpointFunnelTest {
    @Test
    fun `metrics endpoint exposes funnel and slo metrics`() = testApplication {
        application {
            val registry = Observability.install(this)
            val funnel = FunnelMetrics(registry)
            funnel.recordPostView()
            funnel.recordCtaClick()
            funnel.recordBotStart()
            funnel.recordBreakingLatency(Instant.now(), Instant.now().minusSeconds(90))
        }

        val body = client.get("/metrics").bodyAsText()
        assertTrue(body.contains("post_views_total"))
        assertTrue(body.contains("cta_click_total"))
        assertTrue(body.contains("bot_start_total"))
        assertTrue(body.contains("breaking_publish_latency_seconds_bucket"))
    }
}
