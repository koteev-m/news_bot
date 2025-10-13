package audit

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val canonicalJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

enum class AuditActorType {
    USER,
    SERVICE,
    SYSTEM
}

data class AuditEvent(
    val actorType: AuditActorType,
    val actorId: String?,
    val tenantId: Long?,
    val action: String,
    val resource: String,
    val meta: JsonObject = buildJsonObject { },
    val occurredAt: Instant = Instant.now()
)

data class LedgerRecord(
    val seqId: Long,
    val occurredAt: Instant,
    val actorType: AuditActorType,
    val actorId: String?,
    val tenantId: Long?,
    val action: String,
    val resource: String,
    val meta: JsonObject,
    val prevHash: String,
    val hash: String
)

data class AuditCheckpoint(
    val day: LocalDate,
    val lastSeqId: Long,
    val rootHash: String,
    val signature: String,
    val createdAt: Instant
)

object HashUtil {
    fun sha256Hex(vararg parts: String): String {
        val joined = parts.joinToString(separator = "|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    fun canonicalJsonString(element: JsonElement): String {
        return canonicalJson.encodeToString(element.canonical())
    }

    private fun JsonElement.canonical(): JsonElement = when (this) {
        is JsonObject -> JsonObject(entries
            .sortedBy { it.key }
            .map { (key, value) -> key to value.canonical() }
            .toMap()
        )
        is JsonArray -> JsonArray(this.map { it.canonical() })
        is JsonPrimitive -> this
        JsonNull -> JsonNull
    }

    fun canonicalMeta(meta: JsonObject): String = canonicalJsonString(meta)
}
