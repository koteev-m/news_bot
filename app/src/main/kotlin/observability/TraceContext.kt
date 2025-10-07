package observability

import http.TraceContext as HttpTraceContext
import kotlin.coroutines.CoroutineContext

typealias TraceContext = HttpTraceContext

fun CoroutineContext.currentTraceIdOrNull(): String? = this[TraceContext]?.traceId
