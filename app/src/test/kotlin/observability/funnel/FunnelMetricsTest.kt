package observability.funnel

import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunnelMetricsTest {
    @Test
    fun `counters increment and timer registers histogram`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = FunnelMetrics(registry)

        metrics.recordPostView()
        metrics.recordCtaClick()
        metrics.recordBotStart()
        metrics.recordBreakingLatency(Instant.now(), Instant.now().minusSeconds(120))

        assertEquals(1.0, registry.find("post_views_total").counter()?.count())
        assertEquals(1.0, registry.find("cta_click_total").counter()?.count())
        assertEquals(1.0, registry.find("bot_start_total").counter()?.count())

        val timer = registry.find("breaking_publish_latency_seconds").timer()
        assertNotNull(timer)
        assertTrue(registry.scrape().contains("breaking_publish_latency_seconds_bucket"))
    }
}
