package tenancy

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import repo.TenancyRepository

class TenantResolverTest {
    @Test
    fun missing_slug_fails() = testApplication {
        application {
            install(TenantPlugin) {
                repository = object : TenancyRepository() {}
            }
            routing {
                get("/ping") { call.respond("ok") }
            }
        }
        val resp = client.get("/ping")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
