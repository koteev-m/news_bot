package routes

import analytics.AnalyticsPort
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import security.userIdOrNull

fun Route.redirectRoutes(analytics: AnalyticsPort = AnalyticsPort.Noop) {
    get("/go/{id}") {
        val id = call.parameters["id"].orEmpty()
        analytics.track(
            type = "cta_click",
            userId = call.userIdOrNull?.toLongOrNull(),
            source = "redirect",
            props = mapOf("id" to id),
        )
        val targetId = id.ifBlank { "news_bot" }
        call.respondRedirect("https://t.me/$targetId", permanent = false)
    }
}
