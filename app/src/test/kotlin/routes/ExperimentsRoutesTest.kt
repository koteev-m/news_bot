package routes

import ab.Assignment
import ab.Experiment
import ab.ExperimentsPort
import ab.ExperimentsServiceImpl
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.auth.authentication
import io.ktor.http.HttpHeaders
import io.ktor.server.application.createApplicationPlugin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import errors.installErrorPages

class ExperimentsRoutesTest {
    @Test
    fun `returns 401 without authentication`() = testApplication {
        val port = StubExperimentsPort()
        val service = ExperimentsServiceImpl(port)
        application {
            installErrorPages()
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
            install(testAuthPlugin)
            routing { experimentsRoutes(service) }
        }

        val response = client.get("/api/experiments/assignments")
        assertEquals(401, response.status.value)
    }

    @Test
    fun `returns deterministic assignments`() = testApplication {
        val port = StubExperimentsPort()
        val service = ExperimentsServiceImpl(port)
        val userId = 1234L
        val expected = runBlocking { service.assign(userId, "cta_copy") }.variant

        application {
            installErrorPages()
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
            install(testAuthPlugin)
            routing { experimentsRoutes(service) }
        }

        val token = bearerFor(userId.toString())
        val firstResponse = client.get("/api/experiments/assignments") {
            header(HttpHeaders.Authorization, token)
        }
        assertEquals(200, firstResponse.status.value)
        val firstBody = firstResponse.bodyAsText()
        val firstVariant = Json.parseToJsonElement(
            firstBody
        ).jsonArray.first().jsonObject["variant"]?.jsonPrimitive?.content
        assertEquals(expected, firstVariant)

        val secondResponse = client.get("/api/experiments/assignments") {
            header(HttpHeaders.Authorization, token)
        }
        assertEquals(200, secondResponse.status.value)
        val secondBody = secondResponse.bodyAsText()
        val secondVariant = Json.parseToJsonElement(
            secondBody
        ).jsonArray.first().jsonObject["variant"]?.jsonPrimitive?.content
        assertEquals(expected, secondVariant)

        assertTrue(port.assignments.containsKey(userId to "cta_copy"))
    }

    private fun bearerFor(subject: String): String {
        val token = JWT.create().withSubject(subject).sign(Algorithm.HMAC256("test-secret"))
        return "Bearer $token"
    }

    private val testAuthPlugin = createApplicationPlugin(name = "TestAuthExperiments") {
        onCall { call ->
            val header = call.request.headers[HttpHeaders.Authorization]
            if (header != null && header.startsWith("Bearer ")) {
                val token = header.removePrefix("Bearer ")
                val decoded = JWT.decode(token)
                call.authentication.principal(io.ktor.server.auth.jwt.JWTPrincipal(decoded))
            }
        }
    }

    private class StubExperimentsPort : ExperimentsPort {
        private val experiments = listOf(
            Experiment(key = "cta_copy", enabled = true, traffic = mapOf("A" to 60, "B" to 40))
        )
        val assignments = mutableMapOf<Pair<Long, String>, Assignment>()

        override suspend fun list(): List<Experiment> = experiments

        override suspend fun upsert(e: Experiment) {}

        override suspend fun getAssignment(userId: Long, key: String): Assignment? = assignments[userId to key]

        override suspend fun saveAssignment(a: Assignment) {
            assignments.putIfAbsent(a.userId to a.key, a)
        }
    }
}
