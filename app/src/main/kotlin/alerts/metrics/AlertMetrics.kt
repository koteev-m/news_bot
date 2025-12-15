package alerts.metrics

import io.micrometer.core.instrument.MeterRegistry

class AlertMetrics(private val registry: MeterRegistry) {
    fun fire(classId: String, ticker: String, window: String) {
        registry.counter("alert_fire_total", "class", classId, "ticker", ticker, "window", window).increment()
    }

    fun delivered(reason: String) {
        registry.counter("alert_delivered_total", "reason", reason).increment()
    }

    fun suppressed(reason: String) {
        registry.counter("alert_suppressed_total", "reason", reason).increment()
    }
}
