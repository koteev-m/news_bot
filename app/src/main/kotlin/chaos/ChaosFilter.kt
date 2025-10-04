package chaos

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

data class ChaosConfig(
    val enabled: Boolean,
    val latencyMs: Long,
    val jitterMs: Long,
    val errorRate: Double,
    val pathPrefix: String,
    val method: String,
    val percent: Int
)

class ChaosMetrics(registry: MeterRegistry) {
    val latency: Timer = Timer
        .builder("chaos_injected_latency_ms_histogram")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)
    val errors = registry.counter("chaos_injected_errors_total")
}

class ChaosFilterConfig {
    var stateProvider: (() -> ChaosConfig)? = null
    var metricsProvider: (() -> ChaosMetrics)? = null
    var skipWhen: (ApplicationCall) -> Boolean = { false }
}

val ChaosFilter = createApplicationPlugin(name = "ChaosFilter", createConfiguration = ::ChaosFilterConfig) {
    val stateProvider = pluginConfig.stateProvider ?: error("ChaosFilter.stateProvider not configured")
    val metricsProvider = pluginConfig.metricsProvider ?: error("ChaosFilter.metricsProvider not configured")

    onCall { call ->
        if (pluginConfig.skipWhen(call)) {
            return@onCall
        }
        maybeInjectChaos(call, stateProvider(), metricsProvider())
    }
}

suspend fun maybeInjectChaos(call: ApplicationCall, cfg: ChaosConfig, metrics: ChaosMetrics): Boolean {
    if (!cfg.enabled) {
        return false
    }
    val path = call.request.path()
    if (cfg.pathPrefix.isNotEmpty() && !path.startsWith(cfg.pathPrefix)) {
        return false
    }
    val method = call.request.httpMethod.value.uppercase()
    val targetMethod = cfg.method.uppercase()
    if (targetMethod != "ANY" && targetMethod != method) {
        return false
    }
    val percent = cfg.percent.coerceIn(0, 100)
    if (percent == 0) {
        return false
    }
    if (percent < 100 && Random.nextInt(0, 100) >= percent) {
        return false
    }

    val baseDelay = cfg.latencyMs.coerceAtLeast(0)
    val jitter = cfg.jitterMs.coerceAtLeast(0)
    val jitterValue = if (jitter > 0) Random.nextLong(0, jitter + 1) else 0
    val totalDelayMs = baseDelay + jitterValue
    if (totalDelayMs > 0) {
        val sample = Timer.start()
        delay(totalDelayMs.milliseconds)
        sample.stop(metrics.latency)
    }

    val errorRate = cfg.errorRate.coerceIn(0.0, 1.0)
    if (errorRate > 0.0 && Random.nextDouble() < errorRate) {
        metrics.errors.increment()
        call.respondText("chaos injected", status = HttpStatusCode.ServiceUnavailable)
        return true
    }

    return false
}
