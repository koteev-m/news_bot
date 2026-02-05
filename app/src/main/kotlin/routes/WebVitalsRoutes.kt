package routes

import common.runCatchingNonFatal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import observability.WebVitals

@Serializable
private data class VitalEvent(
    val name: String,
    val value: Double,
    val page: String? = null,
    val navType: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

fun Route.webVitalsRoutes(vitals: WebVitals) {
    post("/vitals") {
        val text = call.receiveText()
        val events =
            runCatchingNonFatal {
                json.decodeFromString<List<VitalEvent>>(text)
            }.getOrElse {
                listOf(json.decodeFromString<VitalEvent>(text))
            }

        events.forEach { event ->
            vitals.record(event.name, event.value, event.page, event.navType)
        }

        call.respond(HttpStatusCode.Accepted)
    }
}
