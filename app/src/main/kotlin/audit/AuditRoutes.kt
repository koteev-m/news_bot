package audit

import common.runCatchingNonFatal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import repo.AuditLedgerRepository
import java.time.LocalDate

fun Route.auditRoutes(repository: AuditLedgerRepository) {
    route("/api/audit") {
        get("/ledger/last") {
            val record = repository.getLastRecord()
            if (record == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "ledger_empty"))
                return@get
            }
            call.respond(record.toJson())
        }

        get("/checkpoint/{day}") {
            val dayParam = call.parameters["day"]
            val day = dayParam?.let { runCatchingNonFatal { LocalDate.parse(it) }.getOrNull() }
            if (day == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_day"))
                return@get
            }
            val checkpoint = repository.getCheckpoint(day)
            if (checkpoint == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "checkpoint_not_found"))
                return@get
            }
            call.respond(checkpoint.toJson())
        }
    }
}

private fun audit.LedgerRecord.toJson(): JsonObject =
    buildJsonObject {
        put("seqId", seqId)
        put("occurredAt", occurredAt.toString())
        put("actorType", actorType.name.lowercase())
        actorId?.let { put("actorId", it) }
        tenantId?.let { put("tenantId", it) }
        put("action", action)
        put("resource", resource)
        put("prevHash", prevHash)
        put("hash", hash)
        putJsonObject("meta") {
            meta.forEach { (key, value) ->
                put(key, value)
            }
        }
    }

private fun audit.AuditCheckpoint.toJson(): JsonObject =
    buildJsonObject {
        put("day", day.toString())
        put("lastSeqId", lastSeqId)
        put("rootHash", rootHash)
        put("signature", signature)
        put("createdAt", createdAt.toString())
    }
