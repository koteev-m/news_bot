package routes

import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import observability.WebVitals
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class WebVitalsRoutesTest {
    @Test
    fun accept_single_and_batch_vitals() = testApplication {
        val reg = SimpleMeterRegistry()
        val vitals = WebVitals(reg)
        application {
            routing { webVitalsRoutes(vitals) }
        }

        val one = client.post("/vitals") {
            headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
            setBody("""{"name":"LCP","value":1234,"page":"/#/", "navType":"navigate"}""")
        }
        assertEquals(HttpStatusCode.Accepted, one.status)

        val batch = client.post("/vitals") {
            headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
            setBody(
                """[
              {"name":"CLS","value":0.02,"page":"/#/import"},
              {"name":"FID","value":24}
            ]""".trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Accepted, batch.status)
    }
}
