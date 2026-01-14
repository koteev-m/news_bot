package http

import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.Volatile

enum class CbState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

class CircuitBreaker(
    private val service: String,
    private val cfg: CircuitBreakerCfg,
    private val metrics: IntegrationsMetrics,
    private val clock: Clock
) {
    private val mutex = Mutex()
    private val failures: ArrayDeque<Instant> = ArrayDeque()
    private val stateValue = AtomicInteger(CbState.CLOSED.ordinal)
    @Volatile
    private var state: CbState = CbState.CLOSED
    private var openedAt: Instant? = null
    private var halfOpenInFlight: Int = 0

    init {
        metrics.cbStateGauge(service) { stateValue.get() }
        stateValue.set(state.ordinal)
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        val callState = prepareCall()
        return try {
            val result = block()
            onSuccess()
            result
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            onFailure(ex)
            throw ex
        } finally {
            if (callState == CbState.HALF_OPEN) {
                releaseHalfOpenPermit()
            }
        }
    }

    val currentState: CbState
        get() = state

    private suspend fun prepareCall(): CbState {
        return mutex.withLock {
            val now = clock.instant()
            when (state) {
                CbState.OPEN -> {
                    if (openedAt?.let { Duration.between(it, now).seconds >= cfg.openSeconds } == true) {
                        transitionToHalfOpen()
                    } else {
                        throw CircuitBreakerOpenException(service)
                    }
                }
                CbState.CLOSED -> evictExpiredFailures(now)
                CbState.HALF_OPEN -> {
                    // keep state
                }
            }
            if (state == CbState.HALF_OPEN) {
                if (halfOpenInFlight >= cfg.halfOpenMaxCalls) {
                    throw CircuitBreakerOpenException(service)
                }
                halfOpenInFlight += 1
            }
            state
        }
    }

    private suspend fun onSuccess() {
        mutex.withLock {
            val now = clock.instant()
            when (state) {
                CbState.HALF_OPEN -> {
                    state = CbState.CLOSED
                    stateValue.set(state.ordinal)
                    failures.clear()
                    openedAt = null
                    halfOpenInFlight = 0
                }
                CbState.CLOSED -> evictExpiredFailures(now)
                CbState.OPEN -> {
                    // unreachable during call
                }
            }
        }
    }

    private suspend fun onFailure(cause: Throwable) {
        if (cause is CancellationException) {
            return
        }
        mutex.withLock {
            val now = clock.instant()
            when (state) {
                CbState.HALF_OPEN -> {
                    open(now)
                }
                CbState.CLOSED -> {
                    recordFailure(now)
                }
                CbState.OPEN -> {
                    // ignored
                }
            }
        }
    }

    private suspend fun releaseHalfOpenPermit() {
        mutex.withLock {
            if (halfOpenInFlight > 0) {
                halfOpenInFlight -= 1
            }
        }
    }

    private fun recordFailure(now: Instant) {
        failures.addLast(now)
        evictExpiredFailures(now)
        if (failures.size >= cfg.failuresThreshold) {
            open(now)
        }
    }

    private fun evictExpiredFailures(now: Instant) {
        val windowAgo = now.minusSeconds(cfg.windowSeconds)
        while (failures.isNotEmpty() && failures.first() < windowAgo) {
            failures.removeFirst()
        }
    }

    private fun open(now: Instant) {
        state = CbState.OPEN
        stateValue.set(state.ordinal)
        openedAt = now
        halfOpenInFlight = 0
        failures.clear()
        metrics.cbOpenCounter(service).increment()
    }

    private fun transitionToHalfOpen() {
        state = CbState.HALF_OPEN
        stateValue.set(state.ordinal)
        openedAt = null
        halfOpenInFlight = 0
    }
}

class CircuitBreakerOpenException(service: String) : IOException("Circuit breaker OPEN for $service")
