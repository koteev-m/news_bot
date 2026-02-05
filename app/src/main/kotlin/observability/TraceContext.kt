package observability

import kotlin.coroutines.CoroutineContext
import http.TraceContext as HttpTraceContext

typealias TraceContext = HttpTraceContext

fun CoroutineContext.currentTraceIdOrNull(): String? = this[TraceContext]?.traceId
