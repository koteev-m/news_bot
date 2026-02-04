package alerts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AlertsRoutesTest : FunSpec({
    fun String.errorMessage(): String? =
        Json.parseToJsonElement(this).jsonObject["error"]?.jsonPrimitive?.content

    fun Application.minimalAlertsModule(internalToken: String? = "secret") {
        install(ContentNegotiation) { json() }
        val service = AlertsService(
            AlertsRepositoryMemory(),
            EngineConfig(zoneId = java.time.ZoneOffset.UTC),
            SimpleMeterRegistry()
        )
        routing { alertsRoutes(service, internalToken) }
    }

    test("snapshot route returns transition") {
        testApplication {
            application { minimalAlertsModule() }
            val snapshot = MarketSnapshot(
                tsEpochSec = 1_700_043_200L,
                userId = 9,
                items = listOf(SignalItem("R", "breakout", "daily", pctMove = 1.0))
            )
            val response = client.post("/internal/alerts/snapshot") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(MarketSnapshot.serializer(), snapshot))
                header(INTERNAL_TOKEN_HEADER, "secret")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("state route returns current state") {
        testApplication {
            application { minimalAlertsModule() }
            val response = client.get("/internal/alerts/state?userId=123") {
                header(INTERNAL_TOKEN_HEADER, "secret")
            }
            response.status shouldBe HttpStatusCode.OK
            val state = Json.decodeFromString(FsmState.serializer(), response.bodyAsText())
            state shouldBe FsmState.IDLE
        }
    }

    test("state route rejects wrong token") {
        testApplication {
            application { minimalAlertsModule(internalToken = "token123") }
            val response = client.get("/internal/alerts/state?userId=123") {
                header(INTERNAL_TOKEN_HEADER, "wrong-token")
            }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText().errorMessage() shouldBe "forbidden"
        }
    }

    test("state route requires userId") {
        testApplication {
            application { minimalAlertsModule() }
            val response = client.get("/internal/alerts/state") {
                header(INTERNAL_TOKEN_HEADER, "secret")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText().errorMessage() shouldBe "missing userId"
        }
    }

    test("snapshot route is unavailable without configured token") {
        testApplication {
            application { minimalAlertsModule(internalToken = null) }
            val snapshot = MarketSnapshot(
                tsEpochSec = 1_700_043_200L,
                userId = 9,
                items = listOf(SignalItem("R", "breakout", "daily", pctMove = 3.0))
            )
            val response = client.post("/internal/alerts/snapshot") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(MarketSnapshot.serializer(), snapshot))
            }
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.bodyAsText().errorMessage() shouldBe "internal token not configured"
        }
    }

    test("snapshot route rejects missing token header") {
        testApplication {
            application { minimalAlertsModule(internalToken = "token123") }
            val snapshot = MarketSnapshot(
                tsEpochSec = 1_700_043_200L,
                userId = 9,
                items = listOf(SignalItem("R", "breakout", "daily", pctMove = 3.0))
            )
            val response = client.post("/internal/alerts/snapshot") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(MarketSnapshot.serializer(), snapshot))
            }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText().errorMessage() shouldBe "forbidden"
        }
    }

    test("snapshot route rejects wrong token") {
        testApplication {
            application { minimalAlertsModule(internalToken = "token123") }
            val snapshot = MarketSnapshot(
                tsEpochSec = 1_700_043_200L,
                userId = 9,
                items = listOf(SignalItem("R", "breakout", "daily", pctMove = 3.0))
            )
            val response = client.post("/internal/alerts/snapshot") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(MarketSnapshot.serializer(), snapshot))
                header(INTERNAL_TOKEN_HEADER, "wrong-token")
            }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText().errorMessage() shouldBe "forbidden"
        }
    }

    test("state route validates token before userId") {
        testApplication {
            application { minimalAlertsModule(internalToken = "token123") }
            val forbidden = client.get("/internal/alerts/state")
            forbidden.status shouldBe HttpStatusCode.Forbidden
            forbidden.bodyAsText().errorMessage() shouldBe "forbidden"

            val badRequest = client.get("/internal/alerts/state") {
                header(INTERNAL_TOKEN_HEADER, "token123")
            }
            badRequest.status shouldBe HttpStatusCode.BadRequest
            badRequest.bodyAsText().errorMessage() shouldBe "missing userId"
        }
    }
})
