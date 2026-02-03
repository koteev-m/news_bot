package observability.adapters

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import observability.DomainMetrics

class NewsMetricsAdapterTest {
    @Test
    fun `setDedupRatio clamps and updates gauge`() {
        val registry = SimpleMeterRegistry()
        val metrics = DomainMetrics(registry)
        val adapter = NewsMetricsAdapter(metrics)

        adapter.setDedupRatio(-0.5)
        assertEquals(0.0, registry.get("news_dedup_ratio").gauge().value(), 0.0001)

        adapter.setDedupRatio(1.5)
        assertEquals(1.0, registry.get("news_dedup_ratio").gauge().value(), 0.0001)

        adapter.setDedupRatio(0.42)
        assertEquals(0.42, registry.get("news_dedup_ratio").gauge().value(), 0.0001)
        assertEquals(0.42, registry.get("dedup_ratio").gauge().value(), 0.0001)
    }
}
