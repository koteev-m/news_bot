package errors

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ErrorPagesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AppException is rendered with catalog message`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(CallId) { retrieve { call -> call.request.headers[HttpHeaders.XRequestId] } }
            installErrorPages()
            routing {
                get("/bad-request") {
                    throw AppException.BadRequest(listOf("name is required"))
                }
            }
        }

        val response = client.get("/bad-request") {
            header(HttpHeaders.XRequestId, "req-42")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("BAD_REQUEST", payload.getValue("code").jsonPrimitive.content)
        assertEquals(
            "Invalid request. Please check the data and try again.",
            payload.getValue("message").jsonPrimitive.content
        )
        assertEquals("req-42", payload.getValue("traceId").jsonPrimitive.content)
        val details = payload.getValue("details").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(details.contains("name is required"))
    }

    @Test
    fun `rate limited errors append retry header`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installErrorPages()
            routing {
                get("/rate-limited") {
                    throw AppException.RateLimited(retryAfterSeconds = 60)
                }
            }
        }

        val response = client.get("/rate-limited")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("60", response.headers["Retry-After"])
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("RATE_LIMITED", payload.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `unexpected errors fallback to internal`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installErrorPages()
            routing {
                get("/boom") {
                    error("boom")
                }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("INTERNAL", payload.getValue("code").jsonPrimitive.content)
        assertEquals(
            "Unexpected error. Please try again later.",
            payload.getValue("message").jsonPrimitive.content
        )
    }

    @Test
    fun `cancellation exceptions are rethrown`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installErrorPages()
            routing {
                get("/cancel") {
                    throw CancellationException("cancelled")
                }
            }
        }

        val result = runCatching { client.get("/cancel") }
        val response = result.getOrNull()
        if (response != null) {
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertNotInternalErrorResponse(response.bodyAsText())
        } else {
            assertIs<CancellationException>(result.exceptionOrNull())
        }
    }

    @Test
    fun `errors are rethrown`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installErrorPages()
            routing {
                get("/assert") {
                    throw AssertionError("boom")
                }
            }
        }

        val result = runCatching { client.get("/assert") }
        val response = result.getOrNull()
        if (response != null) {
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertNotInternalErrorResponse(response.bodyAsText())
        } else {
            assertIs<AssertionError>(result.exceptionOrNull())
        }
    }

    private fun assertNotInternalErrorResponse(payload: String) {
        val parsed = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        val code = parsed["code"]?.jsonPrimitive?.contentOrNull
        if (code == "INTERNAL") {
            val snippet = payload.replace(Regex("[\\r\\n\\t]+"), " ").take(400)
            assertTrue(
                code != "INTERNAL",
                "cancellation/errors should not be rendered as INTERNAL ErrorResponse. payload snippet: \"$snippet\""
            )
        }
    }
}
