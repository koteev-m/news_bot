package observability

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.UUID
import org.slf4j.event.Level

object Observability {
    private val traceIdKey: AttributeKey<String> = AttributeKey("observability-trace-id")

    fun install(app: Application): PrometheusMeterRegistry {
        val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        prometheus.config().meterFilter(MeterFilter.deny { id ->
            id.name == "ktor.http.server.requests" && id.getTag("uri") == "/metrics"
        })

        app.install(CallId) {
            retrieve { call -> call.request.headers[HttpHeaders.XRequestId] }
            retrieve { call -> call.request.headers["traceparent"] }
            generate { generateCallId() }
            verify { it.length in 8..64 }
        }

        app.install(CallLogging) {
            level = Level.INFO
            callIdMdc("requestId")
            mdc("traceId") { call ->
                if (call.attributes.contains(traceIdKey)) call.attributes[traceIdKey] else null
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

        app.intercept(ApplicationCallPipeline.Setup) {
            val traceParent = call.request.headers["traceparent"]
            val traceId = traceParent?.let { parseTraceParent(it) }
            if (traceId != null) {
                call.attributes.put(traceIdKey, traceId)
            }
        }

        app.intercept(ApplicationCallPipeline.Call) {
            if (!call.attributes.contains(traceIdKey)) {
                call.callId?.let { call.attributes.put(traceIdKey, it) }
            }
            proceed()
            call.callId?.let { requestId ->
                call.response.headers.append(HttpHeaders.XRequestId, requestId)
            }
            if (call.attributes.contains(traceIdKey)) {
                val traceId = call.attributes[traceIdKey]
                call.response.headers.append("Trace-Id", traceId)
            }
        }

        app.routing {
            get("/metrics") {
                call.respondText(prometheus.scrape())
            }
        }

        return prometheus
    }

    private fun parseTraceParent(value: String): String? {
        val parts = value.split('-')
        if (parts.size < 3) {
            return null
        }
        val traceId = parts[1]
        return traceId.takeIf { it.length in 8..64 }
    }

    private fun generateCallId(): String = UUID.randomUUID().toString().replace("-", "")
}
