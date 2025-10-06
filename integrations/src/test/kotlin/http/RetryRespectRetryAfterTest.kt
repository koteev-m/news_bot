package http

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RetryRespectRetryAfterTest {

    @Test
    fun retryAfterHeaderIsHonoredOnce() = runTest {
        val cfg = TestHttpFixtures.defaultCfg()
        val metrics = TestHttpFixtures.metrics()
        val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

        var called = 0
        val http = TestHttpFixtures.client(cfg, metrics, clock) {
            addHandler {
                called++
                if (called == 1) {
                    respond(
                        content = """{"error":"rate"}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.RetryAfter, listOf("1"))
                    )
                } else {
                    respond(
                        content = """{"ok":true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
        }
        val response = http.get("https://example.test/retry-after")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(called >= 2)

        http.close()
    }
}
