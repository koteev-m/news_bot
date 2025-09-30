package http

data class IntegrationsHttpConfig(
    val userAgent: String,
    val timeoutMs: TimeoutMs,
    val retry: RetryCfg,
    val circuitBreaker: CircuitBreakerCfg
)

data class TimeoutMs(
    val connect: Long,
    val socket: Long,
    val request: Long
)

data class RetryCfg(
    val maxAttempts: Int,
    val baseBackoffMs: Long,
    val jitterMs: Long,
    val respectRetryAfter: Boolean,
    val retryOn: List<Int>
)

data class CircuitBreakerCfg(
    val failuresThreshold: Int,
    val windowSeconds: Long,
    val openSeconds: Long,
    val halfOpenMaxCalls: Int
)
