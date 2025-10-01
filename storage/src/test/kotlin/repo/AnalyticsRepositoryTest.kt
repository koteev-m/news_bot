package repo

import it.TestDb
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsRepositoryTest {
    @Test
    fun `track inserts analytics event`() = runBlocking {
        TestDb.withMigratedDatabase {
            val repository = AnalyticsRepository()
            val ts = Instant.parse("2024-01-01T00:00:00Z")

            repository.track(
                type = "post_published",
                userId = 123L,
                source = "test",
                sessionId = "abc-session",
                props = mapOf("cluster_key" to "cluster-1", "attempt" to 2, "success" to true, "optional" to null),
                ts = ts
            )

            val events = TestDb.tx {
                EventsTable.selectAll().map { row ->
                    val json = row[EventsTable.props]
                    EventSnapshot(
                        ts = row[EventsTable.ts].toInstant(),
                        userId = row[EventsTable.userId],
                        type = row[EventsTable.type],
                        source = row[EventsTable.eventSource],
                        sessionId = row[EventsTable.sessionId],
                        clusterKey = json["cluster_key"]?.jsonPrimitive?.content,
                        attempt = json["attempt"]?.jsonPrimitive?.content,
                        success = json["success"]?.jsonPrimitive?.content,
                        optional = json["optional"]?.jsonPrimitive?.content,
                    )
                }
            }

            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(ts, event.ts)
            assertEquals(123L, event.userId)
            assertEquals("post_published", event.type)
            assertEquals("test", event.source)
            assertEquals("abc-session", event.sessionId)
            assertEquals("cluster-1", event.clusterKey)
            assertEquals("2", event.attempt)
            assertEquals("true", event.success)
            assertNull(event.optional)
        }
    }

    private data class EventSnapshot(
        val ts: Instant,
        val userId: Long?,
        val type: String,
        val source: String?,
        val sessionId: String?,
        val clusterKey: String?,
        val attempt: String?,
        val success: String?,
        val optional: String?,
    )
}
