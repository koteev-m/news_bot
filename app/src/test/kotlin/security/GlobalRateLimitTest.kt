package security

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlobalRateLimitTest {
    @Test
    fun `rate limit throttles and refills`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig().apply {
                        put("security.rateLimit.capacity", "2")
                        put("security.rateLimit.refillPerMinute", "2")
                        put("security.rateLimit.burst", "0")
                    }
            }

            application {
                installGlobalRateLimit()
                routing {
                    get("/ping") {
                        call.respondText("pong")
                    }
                }
            }

            repeat(2) { attempt ->
                val response = client.get("/ping")
                assertEquals(HttpStatusCode.OK, response.status, "attempt ${attempt + 1} should pass")
            }

            val limited = client.get("/ping")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            val retryAfter = limited.headers[HttpHeaders.RetryAfter]
            val retryValue = retryAfter?.toIntOrNull()
            assertNotNull(retryValue)
            assertTrue(retryValue >= 1, "Retry-After must be at least one second")
            assertTrue(limited.bodyAsText().contains("rate_limited"))

            delay(1500)
            val afterDelay = client.get("/ping")
            assertEquals(HttpStatusCode.OK, afterDelay.status)
        }
}
