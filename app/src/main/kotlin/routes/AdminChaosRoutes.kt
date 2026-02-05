package routes

import chaos.ChaosConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import security.userIdOrNull
import java.util.concurrent.atomic.AtomicReference

@Suppress("LongParameterList")
data class ChaosPatch(
    val enabled: Boolean? = null,
    val latencyMs: Long? = null,
    val jitterMs: Long? = null,
    val errorRate: Double? = null,
    val pathPrefix: String? = null,
    val method: String? = null,
    val percent: Int? = null,
)

class ChaosState(
    private val featuresEnabled: Boolean,
    private val environmentAllowed: Boolean,
    initial: ChaosConfig,
) {
    private val rawConfig = AtomicReference(sanitize(initial))

    @Volatile
    var cfg: ChaosConfig = effective(rawConfig.get())
        private set

    private fun sanitize(config: ChaosConfig): ChaosConfig {
        val latency = if (config.latencyMs < 0) 0 else config.latencyMs
        val jitter = if (config.jitterMs < 0) 0 else config.jitterMs
        val errorRate =
            when {
                config.errorRate.isNaN() -> 0.0
                config.errorRate < 0.0 -> 0.0
                config.errorRate > 1.0 -> 1.0
                else -> config.errorRate
            }
        val percent =
            when {
                config.percent < 0 -> 0
                config.percent > MAX_PERCENT -> MAX_PERCENT
                else -> config.percent
            }
        val method = config.method.uppercase()
        val pathPrefix = config.pathPrefix.ifBlank { "/" }
        return config.copy(
            latencyMs = latency,
            jitterMs = jitter,
            errorRate = errorRate,
            pathPrefix = pathPrefix,
            method = method,
            percent = percent,
        )
    }

    private fun effective(sanitized: ChaosConfig): ChaosConfig {
        val enabled = sanitized.enabled && featuresEnabled && environmentAllowed
        return sanitized.copy(enabled = enabled)
    }

    fun snapshot(): ChaosConfig = cfg

    fun update(patch: ChaosPatch): ChaosConfig {
        val current = rawConfig.get()
        val merged =
            ChaosConfig(
                enabled = patch.enabled ?: current.enabled,
                latencyMs = patch.latencyMs ?: current.latencyMs,
                jitterMs = patch.jitterMs ?: current.jitterMs,
                errorRate = patch.errorRate ?: current.errorRate,
                pathPrefix = patch.pathPrefix ?: current.pathPrefix,
                method = patch.method ?: current.method,
                percent = patch.percent ?: current.percent,
            )
        val sanitized = sanitize(merged)
        rawConfig.set(sanitized)
        val effectiveConfig = effective(sanitized)
        cfg = effectiveConfig
        return effectiveConfig
    }
}

fun Route.adminChaosRoutes(
    state: ChaosState,
    adminUserIds: Set<Long>,
) {
    route("/api/admin/chaos") {
        get {
            val userId =
                call.userIdOrNull?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
            if (userId !in adminUserIds) {
                return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
            }
            call.respond(HttpStatusCode.OK, state.snapshot())
        }
        patch {
            val userId =
                call.userIdOrNull?.toLongOrNull()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            if (userId !in adminUserIds) {
                return@patch call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
            }
            val patch = call.receive<ChaosPatch>()
            state.update(patch)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private const val MAX_PERCENT = 100
