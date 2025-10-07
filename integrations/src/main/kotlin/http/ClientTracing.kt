package http

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey as OtelAttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapSetter

private val SpanAttributeKey: AttributeKey<Span> = AttributeKey("ClientTracingSpan")
private val ScopeAttributeKey: AttributeKey<Scope> = AttributeKey("ClientTracingScope")
private val StatusAttributeKey: AttributeKey<Int> = AttributeKey("ClientTracingStatus")

private val httpRequestMethodKey: OtelAttributeKey<String> = OtelAttributeKey.stringKey("http.request.method")
private val urlFullKey: OtelAttributeKey<String> = OtelAttributeKey.stringKey("url.full")
private val urlSchemeKey: OtelAttributeKey<String> = OtelAttributeKey.stringKey("url.scheme")
private val httpResponseStatusKey: OtelAttributeKey<Long> = OtelAttributeKey.longKey("http.response.status_code")

private object HeaderSetter : TextMapSetter<HttpRequestBuilder> {
    override fun set(carrier: HttpRequestBuilder?, key: String, value: String) {
        carrier?.headers?.remove(key)
        carrier?.headers?.append(key, value)
    }
}

val ClientTracing = createClientPlugin("ClientTracing") {
    val tracer: Tracer = GlobalOpenTelemetry.getTracer("newsbot-app/ktor-client")

    onRequest { request, _ ->
        val span = tracer.spanBuilder("HTTP ${request.method.value}")
            .setSpanKind(SpanKind.CLIENT)
            .setParent(Context.current())
            .setAttribute(httpRequestMethodKey, request.method.value)
            .setAttribute(urlFullKey, request.url.buildString())
            .setAttribute(urlSchemeKey, request.url.protocol.name)
            .startSpan()
        val scope = span.makeCurrent()
        request.attributes.put(SpanAttributeKey, span)
        request.attributes.put(ScopeAttributeKey, scope)
        GlobalOpenTelemetry.getPropagators().textMapPropagator.inject(Context.current(), request, HeaderSetter)
        request.executionContext.invokeOnCompletion { cause ->
            finishSpan(request.attributes, cause)
        }
    }

    onResponse { response: HttpResponse ->
        response.call.request.attributes.put(StatusAttributeKey, response.status.value)
    }
}

private fun finishSpan(attributes: Attributes, failure: Throwable?) {
    val span = attributes.getIfPresent(SpanAttributeKey) ?: return
    val scope = attributes.getIfPresent(ScopeAttributeKey)
    try {
        attributes.getIfPresent(StatusAttributeKey)?.let { code ->
            span.setAttribute(httpResponseStatusKey, code.toLong())
        }
        failure?.let { error ->
            span.recordException(error)
            span.setStatus(StatusCode.ERROR)
        }
    } finally {
        scope?.close()
        span.end()
        attributes.removeIfPresent(ScopeAttributeKey)
        attributes.removeIfPresent(SpanAttributeKey)
        attributes.removeIfPresent(StatusAttributeKey)
    }
}

private fun <T : Any> Attributes.getIfPresent(key: AttributeKey<T>): T? = if (contains(key)) get(key) else null

private fun <T : Any> Attributes.removeIfPresent(key: AttributeKey<T>) {
    if (contains(key)) {
        remove(key)
    }
}
