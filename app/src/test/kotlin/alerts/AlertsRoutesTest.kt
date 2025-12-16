package alerts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class AlertsRoutesTest : FunSpec({
    fun Application.minimalAlertsModule() {
        install(ContentNegotiation) { json() }
        val service = AlertsService(AlertsRepositoryMemory(), EngineConfig(zoneId = java.time.ZoneOffset.UTC), SimpleMeterRegistry())
        routing { alertsRoutes(service) }
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
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("state route returns current state") {
        testApplication {
            application { minimalAlertsModule() }
            val response = client.get("/internal/alerts/state?userId=123")
            response.status shouldBe HttpStatusCode.OK
            val state = Json.decodeFromString(FsmState.serializer(), response.bodyAsText())
            state shouldBe FsmState.IDLE
        }
    }

    test("state route requires userId") {
        testApplication {
            application { minimalAlertsModule() }
            val response = client.get("/internal/alerts/state")
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldBe "{\"error\":\"missing userId\"}"
        }
    }
})
