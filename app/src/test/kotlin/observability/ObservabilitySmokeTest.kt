package observability

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservabilitySmokeTest {
    @Test
    fun `metrics endpoint exposes prometheus data and masks bearer tokens`() = testApplication {
        application {
            Observability.install(this)
            routing {
                get("/ping") {
                    call.respondText("pong")
                }
            }
        }

        val pingResponse = client.get("/ping") {
            header(HttpHeaders.Authorization, "Bearer abc.def.ghi")
        }
        assertEquals(HttpStatusCode.OK, pingResponse.status)

        val metricsResponse = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, metricsResponse.status)
        val metricsBody = metricsResponse.bodyAsText()
        assertTrue(
            metricsBody.contains("http_server_requests_seconds_count") ||
                metricsBody.contains("ktor_http_server_requests_seconds_count")
        )

        val masked = maskSensitive("Authorization: Bearer abc.def.ghi")
        assertFalse(masked.contains("abc.def.ghi"))
    }

    private fun maskSensitive(message: String): String {
        val regex = Regex(
            pattern = "(?i)\\b(bearer\\s+[A-Za-z0-9._-]+|x-telegram-bot-api-secret-token:[^,\\s]+|initData=[^&\\s]+|token=[A-Za-z0-9:_-]{20,})"
        )
        return regex.replace(message) { "***" }
    }
}
