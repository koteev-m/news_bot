package observability

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

class WebVitals(
    registry: MeterRegistry,
) {
    private val lcp = SummaryCache(registry, "web_vitals_lcp_seconds") { it / 1000.0 }
    private val cls = SummaryCache(registry, "web_vitals_cls")
    private val fid = SummaryCache(registry, "web_vitals_fid_ms")
    private val inp = SummaryCache(registry, "web_vitals_inp_ms")
    private val ttfb = SummaryCache(registry, "web_vitals_ttfb_ms")

    fun record(
        name: String,
        value: Double,
        page: String?,
        navType: String?,
    ) {
        val normPage = normalize(page, "/", 64)
        val normNav = normalize(navType, "navigate", 32)
        when (name.uppercase()) {
            "LCP" -> lcp.record(value, normPage, normNav)
            "CLS" -> cls.record(value, normPage, normNav)
            "FID" -> fid.record(value, normPage, normNav)
            "INP" -> inp.record(value, normPage, normNav)
            "TTFB" -> ttfb.record(value, normPage, normNav)
        }
    }

    private fun normalize(
        value: String?,
        fallback: String,
        max: Int,
    ): String {
        val candidate = value?.ifBlank { null } ?: fallback
        return candidate.take(max)
    }

    private class SummaryCache(
        private val registry: MeterRegistry,
        private val name: String,
        private val convert: (Double) -> Double = { it },
    ) {
        private val cache = ConcurrentHashMap<Pair<String, String>, DistributionSummary>()

        fun record(
            raw: Double,
            page: String,
            nav: String,
        ) {
            val summary =
                cache.computeIfAbsent(page to nav) { (p, n) ->
                    DistributionSummary
                        .builder(name)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .tags("page", p, "nav", n)
                        .register(registry)
                }
            summary.record(convert(raw))
        }
    }
}
