package observability

import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.response.respondText
import io.ktor.server.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceHeadersTest {
    @Test
    fun echo_request_and_trace_ids_in_response_headers() = testApplication {
        application {
            Observability.install(this)
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }
        val resp = client.get("/ping") { header("X-Request-Id", "test-req-123") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("test-req-123", resp.headers["X-Request-Id"])
        assertEquals("test-req-123", resp.headers["Trace-Id"])
    }
}
