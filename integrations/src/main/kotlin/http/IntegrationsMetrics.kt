package http

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap

class IntegrationsMetrics(private val registry: MeterRegistry) {
    private val retryCounters = ConcurrentHashMap<String, Counter>()
    private val retryAfterCounters = ConcurrentHashMap<String, Counter>()
    private val cbOpenCounters = ConcurrentHashMap<String, Counter>()
    private val cbStateSuppliers = ConcurrentHashMap<String, () -> Int>()
    private val requestTimers = ConcurrentHashMap<Pair<String, String>, Timer>()

    fun retryCounter(service: String): Counter = retryCounters.computeIfAbsent(service) {
        registry.counter("integrations_retry_total", "service", it)
    }

    fun retryAfterHonored(service: String): Counter = retryAfterCounters.computeIfAbsent(service) {
        registry.counter("integrations_retry_after_honored_total", "service", it)
    }

    fun cbOpenCounter(service: String): Counter = cbOpenCounters.computeIfAbsent(service) {
        registry.counter("integrations_cb_open_total", "service", it)
    }

    fun cbStateGauge(service: String, supplier: () -> Int) {
        cbStateSuppliers.computeIfAbsent(service) {
            registry.gauge("integrations_cb_state", Tags.of("service", service), supplier) { it().toDouble() }
            supplier
        }
    }

    fun timerSample(): Timer.Sample = Timer.start(registry)

    fun stopTimer(sample: Timer.Sample, service: String, outcome: String) {
        sample.stop(requestTimer(service, outcome))
    }

    fun requestTimer(service: String, outcome: String): Timer = requestTimers.computeIfAbsent(service to outcome) {
        registry.timer("integrations_request_seconds", "service", service, "outcome", outcome)
    }

}
