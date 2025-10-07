package routes

import analytics.AnalyticsPort
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import errors.installErrorPages
import repo.FaqItem
import repo.SupportRepository
import repo.SupportTicket
import security.RateLimitConfig
import security.RateLimiter

data class AnalyticsEvent(
    val type: String,
    val userId: Long?,
    val source: String?,
    val props: Map<String, Any?>
)

class SupportRoutesTest {
    @Test
    fun `GET FAQ returns empty list`() = testApplication {
        val repo = FakeSupportRepository()
        val analytics = RecordingAnalytics()
        val limiter = RateLimiter(RateLimitConfig(5, 5), Clock.systemUTC())

        application {
            installErrorPages()
            install(ContentNegotiation) { json() }
            routing {
                supportRoutes(repo, analytics, limiter)
            }
        }

        val response = client.get("/api/support/faq/en")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    @Test
    fun `feedback is rate limited`() = testApplication {
        val repo = FakeSupportRepository()
        val analytics = RecordingAnalytics()
        val limiter = RateLimiter(RateLimitConfig(capacity = 1, refillPerMinute = 1), Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC))

        application {
            installErrorPages()
            install(ContentNegotiation) { json() }
            routing {
                supportRoutes(repo, analytics, limiter)
            }
        }

        val body = """{"category":"idea","subject":"Hi","message":"Test"}"""
        val first = client.post("/api/support/feedback") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        assertEquals(HttpStatusCode.Accepted, first.status)

        val second = client.post("/api/support/feedback") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        val retryAfter = second.headers[HttpHeaders.RetryAfter]
        assertEquals("60", retryAfter)
    }

    @Test
    fun `feedback accepted triggers analytics`() = testApplication {
        val repo = FakeSupportRepository()
        val analytics = RecordingAnalytics()
        val limiter = RateLimiter(RateLimitConfig(5, 5), Clock.systemUTC())

        application {
            installErrorPages()
            install(ContentNegotiation) { json() }
            routing {
                supportRoutes(repo, analytics, limiter)
            }
        }

        val response = client.post("/api/support/feedback") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"category":"bug","subject":"Broken","message":"Something"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val recorded = analytics.events.singleOrNull()
        assertNotNull(recorded)
        assertEquals("support_feedback_submitted", recorded.type)
        assertEquals("bug", recorded.props["category"])
        assertEquals("en", recorded.props["locale"])
        assertNotNull(recorded.props["ticket_id"])
    }

    private class FakeSupportRepository : SupportRepository() {
        private val lock = Mutex()
        private val storedFaq = mutableMapOf<String, List<FaqItem>>()
        private val tickets = mutableListOf<SupportTicket>()
        private var nextId = 1L

        override suspend fun listFaq(localeValue: String): List<FaqItem> = lock.withLock {
            storedFaq[localeValue] ?: emptyList()
        }

        override suspend fun createTicket(ticket: SupportTicket): Long = lock.withLock {
            val id = nextId++
            tickets += ticket.copy(ticketId = id, ts = Instant.EPOCH)
            id
        }

        override suspend fun listTickets(status: String?, limit: Int): List<SupportTicket> = lock.withLock {
            tickets.take(limit)
        }

        override suspend fun updateStatus(id: Long, statusValue: String): Int = lock.withLock {
            val index = tickets.indexOfFirst { it.ticketId == id }
            if (index >= 0) {
                tickets[index] = tickets[index].copy(status = statusValue)
                1
            } else {
                0
            }
        }
    }

    private class RecordingAnalytics : AnalyticsPort {
        val events = mutableListOf<AnalyticsEvent>()

        override suspend fun track(
            type: String,
            userId: Long?,
            source: String?,
            sessionId: String?,
            props: Map<String, Any?>,
            ts: Instant,
        ) {
            events += AnalyticsEvent(type, userId, source, props)
        }
    }
}
