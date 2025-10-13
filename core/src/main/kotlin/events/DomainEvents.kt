package events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class DomainEvent<T>(
    val type: String,
    val version: Int,
    val id: String,
    val tenantId: Long,
    val occurredAt: String,
    val payload: T
)

object EventCodec {
    val json = Json { encodeDefaults = true }
    fun <T> encode(evt: DomainEvent<T>, serializer: kotlinx.serialization.KSerializer<T>): String {
        val wrapper = DomainEvent(
            type = evt.type,
            version = evt.version,
            id = evt.id,
            tenantId = evt.tenantId,
            occurredAt = evt.occurredAt,
            payload = evt.payload
        )
        return json.encodeToString(DomainEvent.serializer(serializer), wrapper)
    }
}
