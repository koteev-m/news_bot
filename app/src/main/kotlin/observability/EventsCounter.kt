package observability

import io.micrometer.core.instrument.MeterRegistry

class EventsCounter(
    private val registry: MeterRegistry,
) {
    fun inc(type: String) {
        registry.counter("events_total", "type", type).increment()
    }
}
