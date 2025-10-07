package observability

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceHeadersTest {
    @Test
    fun echo_request_id_and_trace_id() = testApplication {
        application {
            Observability.install(this)
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }

        val response = client.get("/ping") {
            header("X-Request-Id", "test-req-123")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("test-req-123", response.headers["X-Request-Id"])
        assertEquals("test-req-123", response.headers["Trace-Id"])
    }
}
