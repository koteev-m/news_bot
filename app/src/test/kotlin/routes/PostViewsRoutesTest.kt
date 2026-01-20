package routes

import alerts.INTERNAL_TOKEN_HEADER
import interfaces.ChannelViewsClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import views.PostViewsService

class PostViewsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `enabled route returns views and increments metrics`() = testApplication {
        val registry = SimpleMeterRegistry()
        val viewsClient = StaticViewsClient(mapOf(1 to 100L, 2 to 50L))
        val service = PostViewsService(viewsClient, registry)

        application {
            install(ContentNegotiation) { json() }
            routing {
                postViewsRoutes(service, enabled = true, internalToken = "secret")
            }
        }

        val response = client.get("/internal/post_views/sync?channel=news&ids=1,2") {
            headers.append(INTERNAL_TOKEN_HEADER, "secret")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("100", payload["1"]?.jsonPrimitive?.content)
        assertEquals("50", payload["2"]?.jsonPrimitive?.content)
        assertEquals(100.0, registry.get("post_views_total").tag("post_id", "1").counter().count())
    }

    @Test
    fun `disabled route returns not implemented`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                postViewsRoutes(null, enabled = false, internalToken = "secret")
            }
        }

        val response = client.get("/internal/post_views/sync?channel=news&ids=1") {
            headers.append(INTERNAL_TOKEN_HEADER, "secret")
        }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
    }

    @Test
    fun `invalid ids return bad request`() = testApplication {
        val registry = SimpleMeterRegistry()
        val service = PostViewsService(StaticViewsClient(emptyMap()), registry)

        application {
            install(ContentNegotiation) { json() }
            routing {
                postViewsRoutes(service, enabled = true, internalToken = "secret")
            }
        }

        val response = client.get("/internal/post_views/sync?channel=news&ids=abc") {
            headers.append(INTERNAL_TOKEN_HEADER, "secret")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `missing internal token returns forbidden`() = testApplication {
        val registry = SimpleMeterRegistry()
        val service = PostViewsService(StaticViewsClient(emptyMap()), registry)

        application {
            install(ContentNegotiation) { json() }
            routing {
                postViewsRoutes(service, enabled = true, internalToken = "secret")
            }
        }

        val response = client.get("/internal/post_views/sync?channel=news&ids=1")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `zero or negative ids return bad request`() = testApplication {
        val registry = SimpleMeterRegistry()
        val service = PostViewsService(StaticViewsClient(emptyMap()), registry)

        application {
            install(ContentNegotiation) { json() }
            routing {
                postViewsRoutes(service, enabled = true, internalToken = "secret")
            }
        }

        val response = client.get("/internal/post_views/sync?channel=news&ids=0,-1") {
            headers.append(INTERNAL_TOKEN_HEADER, "secret")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

private class StaticViewsClient(private val response: Map<Int, Long>) : ChannelViewsClient {
    override suspend fun getViews(channel: String, ids: List<Int>, increment: Boolean): Map<Int, Long> = response
}
