package observability

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.callId
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID

fun Application.installMdcTrace() {
    intercept(ApplicationCallPipeline.Plugins) {
        val id = call.callId ?: call.request.headers["Trace-Id"] ?: UUID.randomUUID().toString()
        MDC.put("requestId", id)
        try {
            proceed()
        } finally {
            MDC.remove("requestId")
        }
    }
}
