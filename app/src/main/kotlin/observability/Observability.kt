package observability

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.UUID
import kotlinx.coroutines.withContext
import org.slf4j.event.Level

object Observability {
    private val traceIdKey: AttributeKey<String> = AttributeKey("observability-trace-id")

    fun install(app: Application): PrometheusMeterRegistry {
        val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        prometheus.config().meterFilter(MeterFilter.deny { id ->
            id.name == "ktor.http.server.requests" && id.getTag("uri") == "/metrics"
        })

        app.install(CallId) {
            header(HttpHeaders.XRequestId)
            header("traceparent")
            generate { UUID.randomUUID().toString() }
            verify { it.length in 8..64 }
        }

        app.install(CallLogging) {
            level = Level.INFO
            callIdMdc("requestId")
            mdc("traceId") { call ->
                call.attributes.getOrNull(traceIdKey)
            }
            filter { call ->
                call.request.path().startsWith("/metrics").not()
            }
            format { call ->
                val method = call.request.httpMethod.value
                val path = call.request.path()
                val status = call.response.status()?.value?.toString() ?: "Unhandled"
                val requestId = call.callId ?: "-"
                "Handled $method $path -> $status (requestId=$requestId)"
            }
        }

        app.install(MicrometerMetrics) {
            registry = prometheus
        }

        app.intercept(ApplicationCallPipeline.Plugins) {
            val traceId = resolveTraceId(call)
            call.attributes.put(traceIdKey, traceId)
            withContext(TraceContext(traceId)) {
                proceed()
            }
        }

        app.sendPipeline.intercept(io.ktor.server.application.ApplicationSendPipeline.Before) {
            val requestId = call.callId ?: call.attributes.getOrNull(traceIdKey)
            val traceId = call.attributes.getOrNull(traceIdKey) ?: requestId
            requestId?.let { call.response.headers.append(HttpHeaders.XRequestId, it, safeOnly = false) }
            traceId?.let { call.response.headers.append("Trace-Id", it, safeOnly = false) }
        }

        app.routing {
            get("/metrics") {
                call.respondText(prometheus.scrape())
            }
        }

        return prometheus
    }

    private fun resolveTraceId(call: ApplicationCall): String {
        val callId = call.callId
        val traceHeader = call.request.header("Trace-Id")?.takeIf { it.isNotBlank() }
        val traceParentId = call.request.header("traceparent")?.let { parseTraceParent(it) }
        return callId ?: traceHeader ?: traceParentId ?: UUID.randomUUID().toString()
    }

    private fun parseTraceParent(value: String): String? {
        val parts = value.split('-')
        if (parts.size < 3) {
            return null
        }
        val traceId = parts[1]
        return traceId.takeIf { it.length in 8..64 }
    }
}

private fun <T : Any> Attributes.getOrNull(key: AttributeKey<T>): T? = if (contains(key)) get(key) else null
