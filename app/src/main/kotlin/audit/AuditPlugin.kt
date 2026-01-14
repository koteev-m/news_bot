package audit

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.*
import io.ktor.server.plugins.callid.callId
import io.ktor.util.AttributeKey
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import observability.currentTraceIdOrNull
import security.userIdOrNull
import tenancy.TenantContextKey
import common.runCatchingNonFatal

class AuditPluginConfig {
    lateinit var auditService: AuditService
    var coroutineScope: CoroutineScope? = null
}

private data class ActorContext(val type: AuditActorType, val id: String?)

val AuditPlugin = createApplicationPlugin(name = "AuditPlugin", createConfiguration = ::AuditPluginConfig) {
    val service = pluginConfig.auditService
    val scope = pluginConfig.coroutineScope

    onCall { call ->
        call.response.pipeline.intercept(ApplicationSendPipeline.After) {
            val actor = resolveActor(call)
            val tenantId = call.attributes.getOrNull(TenantContextKey)?.tenant?.tenantId
            val statusCode = call.response.status()?.value
            val action = buildString {
                append("HTTP ")
                append(call.request.httpMethod.value.uppercase())
                append(' ')
                append(statusBucket(statusCode))
            }
            val meta = buildMeta(call, statusCode)
            val logger = call.application.environment.log
            val task: suspend () -> Unit = {
                runCatchingNonFatal {
                    logEvent(service, actor, action, call.request.uri, meta, tenantId)
                }.onFailure { throwable ->
                    logger.warn("Audit log failed", throwable)
                }
            }
            if (scope != null) {
                scope.launch { task() }
            } else {
                task()
            }
            proceed()
        }
    }
}

private suspend fun logEvent(
    service: AuditService,
    actor: ActorContext,
    action: String,
    resource: String,
    meta: JsonObject,
    tenantId: Long?
) {
    when (actor.type) {
        AuditActorType.USER -> actor.id?.let { service.logUser(it, action, resource, meta, tenantId) }
        AuditActorType.SERVICE -> service.logService(actor.id, action, resource, meta, tenantId)
        AuditActorType.SYSTEM -> service.logSystem(action, resource, meta, tenantId)
    }
}

private fun resolveActor(call: ApplicationCall): ActorContext {
    val userId = call.userIdOrNull?.takeIf { it.isNotBlank() }
    if (userId != null) {
        return ActorContext(AuditActorType.USER, userId)
    }
    val serviceId = call.request.header("X-Service-Name")?.takeIf { it.isNotBlank() }
        ?: call.request.header("X-Client-Id")?.takeIf { it.isNotBlank() }
    if (serviceId != null) {
        return ActorContext(AuditActorType.SERVICE, serviceId)
    }
    val systemActor = call.request.header("X-System-Actor")?.takeIf { it.isNotBlank() }
    return ActorContext(AuditActorType.SYSTEM, systemActor)
}

private fun statusBucket(status: Int?): String = when (status) {
    null -> "unhandled"
    in 100..199 -> "1xx"
    in 200..299 -> "2xx"
    in 300..399 -> "3xx"
    in 400..499 -> "4xx"
    in 500..599 -> "5xx"
    else -> "other"
}

private fun buildMeta(call: ApplicationCall, status: Int?): JsonObject = buildJsonObject {
    call.callId?.takeIf { it.isNotBlank() }?.let { put("requestId", it) }
    val traceId = call.coroutineContext.currentTraceIdOrNull()
        ?: call.request.header("Trace-Id")?.takeIf { it.isNotBlank() }
    traceId?.let { put("traceId", it) }
    call.request.header("X-Forwarded-For")?.takeIf { it.isNotBlank() }?.let { put("forwardedFor", it) }
    call.request.header("User-Agent")?.takeIf { it.isNotBlank() }?.let { put("userAgent", it) }
    status?.let { put("status", it) }
    put("timestamp", Instant.now().toString())
}

private fun <T : Any> io.ktor.util.Attributes.getOrNull(key: AttributeKey<T>): T? =
    if (contains(key)) get(key) else null
