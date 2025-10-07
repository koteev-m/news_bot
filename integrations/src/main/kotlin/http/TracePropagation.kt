package http

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class TraceContext(val traceId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceContext>
}

private fun HttpRequestBuilder.setTraceHeaders(trace: String) {
    headers.remove("X-Request-Id")
    headers.append("X-Request-Id", trace)
    headers.remove("Trace-Id")
    headers.append("Trace-Id", trace)
}

val TracePropagation = createClientPlugin("TracePropagation") {
    onRequest { request, _ ->
        val trace = coroutineContext[TraceContext]?.traceId
        if (!trace.isNullOrBlank()) {
            request.setTraceHeaders(trace)
        }
    }
}
