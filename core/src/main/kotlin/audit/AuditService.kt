package audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

interface AuditLedgerPort {
    suspend fun appendEvent(event: AuditEvent): LedgerRecord
}

class AuditService(private val repository: AuditLedgerPort) {
    suspend fun logUser(
        actorId: String,
        action: String,
        resource: String,
        meta: JsonObject = buildJsonObject { },
        tenantId: Long?
    ) {
        require(actorId.isNotBlank()) { "actorId is required for user events" }
        append(AuditActorType.USER, actorId, action, resource, meta, tenantId)
    }

    suspend fun logService(
        actorId: String?,
        action: String,
        resource: String,
        meta: JsonObject = buildJsonObject { },
        tenantId: Long?
    ) {
        append(AuditActorType.SERVICE, actorId, action, resource, meta, tenantId)
    }

    suspend fun logSystem(
        action: String,
        resource: String,
        meta: JsonObject = buildJsonObject { },
        tenantId: Long?
    ) {
        append(AuditActorType.SYSTEM, null, action, resource, meta, tenantId)
    }

    private suspend fun append(
        actorType: AuditActorType,
        actorId: String?,
        action: String,
        resource: String,
        meta: JsonObject,
        tenantId: Long?
    ) {
        require(action.isNotBlank()) { "action is required" }
        require(resource.isNotBlank()) { "resource is required" }
        val event = AuditEvent(
            actorType = actorType,
            actorId = actorId?.takeIf { it.isNotBlank() },
            tenantId = tenantId,
            action = action,
            resource = resource,
            meta = meta
        )
        repository.appendEvent(event)
    }
}
