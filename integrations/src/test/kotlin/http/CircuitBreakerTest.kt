package http

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class CircuitBreakerTest {
    @Test
    fun `transitions update metrics`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
        val cfg = CircuitBreakerCfg(
            failuresThreshold = 2,
            windowSeconds = 60,
            openSeconds = 30,
            halfOpenMaxCalls = 1
        )
        val breaker = CircuitBreaker("test", cfg, metrics, clock)

        assertEquals(CbState.CLOSED, breaker.currentState)
        assertEquals(0.0, gaugeValue(registry))

        repeat(2) {
            assertFailsWith<IOException> {
                breaker.withPermit { throw IOException("fail-$it") }
            }
        }

        assertEquals(CbState.OPEN, breaker.currentState)
        assertEquals(1.0, gaugeValue(registry))
        assertEquals(1.0, registry.counter("integrations_cb_open_total", "service", "test").count())

        assertFailsWith<CircuitBreakerOpenException> {
            breaker.withPermit { "should not execute" }
        }

        clock.advanceSeconds(31)
        val result = breaker.withPermit {
            assertEquals(CbState.HALF_OPEN, breaker.currentState)
            assertEquals(2.0, gaugeValue(registry))
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(CbState.CLOSED, breaker.currentState)
        assertEquals(0.0, gaugeValue(registry))

        repeat(2) {
            assertFailsWith<IOException> {
                breaker.withPermit { throw IOException("again-$it") }
            }
        }
        assertEquals(CbState.OPEN, breaker.currentState)
        assertEquals(1.0, gaugeValue(registry))
        assertEquals(2.0, registry.counter("integrations_cb_open_total", "service", "test").count())

        clock.advanceSeconds(31)
        assertFailsWith<IOException> {
            breaker.withPermit {
                assertEquals(CbState.HALF_OPEN, breaker.currentState)
                assertEquals(2.0, gaugeValue(registry))
                throw IOException("half-open failure")
            }
        }
        assertEquals(CbState.OPEN, breaker.currentState)
        assertEquals(1.0, gaugeValue(registry))
        assertEquals(3.0, registry.counter("integrations_cb_open_total", "service", "test").count())

        assertFailsWith<CircuitBreakerOpenException> {
            breaker.withPermit { "blocked" }
        }
    }

    private fun gaugeValue(registry: SimpleMeterRegistry): Double =
        registry.find("integrations_cb_state").tags("service", "test").gauge()?.value() ?: error("gauge missing")

    private class MutableClock(initial: Instant) : Clock() {
        private var current: Instant = initial

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
