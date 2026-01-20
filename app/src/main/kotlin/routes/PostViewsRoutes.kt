package routes

import alerts.INTERNAL_TOKEN_HEADER
import common.rethrowIfFatal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import mtproto.MtprotoViewsClientError
import views.PostViewsService

fun Route.postViewsRoutes(service: PostViewsService?, enabled: Boolean, internalToken: String?) {
    suspend fun ApplicationCall.ensureInternalAccess(): Boolean {
        if (internalToken.isNullOrBlank()) {
            respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "internal token not configured"))
            return false
        }
        val provided = request.headers[INTERNAL_TOKEN_HEADER]
        if (provided != internalToken) {
            respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
            return false
        }
        return true
    }

    get("/internal/post_views/sync") {
        if (!call.ensureInternalAccess()) return@get
        if (!enabled || service == null) {
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "mtproto disabled"))
            return@get
        }

        val channel = call.request.queryParameters["channel"]?.trim().orEmpty()
        val idsRaw = call.request.queryParameters["ids"]?.trim().orEmpty()
        if (channel.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "channel is required"))
            return@get
        }
        if (idsRaw.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ids are required"))
            return@get
        }

        val ids = idsRaw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .take(1000)

        if (ids.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ids are required"))
            return@get
        }

        val views = try {
            service.sync(channel, ids)
        } catch (ex: Throwable) {
            rethrowIfFatal(ex)
            val (status, message) = ex.toHttpError()
            call.respond(status, mapOf("error" to message))
            return@get
        }

        val response = views.mapKeys { it.key.toString() }
        call.respond(HttpStatusCode.OK, response)
    }
}

private fun Throwable.toHttpError(): Pair<HttpStatusCode, String> = when (this) {
    is MtprotoViewsClientError.ValidationError -> HttpStatusCode.BadRequest to (message ?: "validation error")
    is MtprotoViewsClientError.TimeoutError -> HttpStatusCode.GatewayTimeout to (message ?: "timeout")
    is MtprotoViewsClientError.HttpStatusError -> HttpStatusCode.BadGateway to (message ?: "gateway error")
    is MtprotoViewsClientError.DeserializationError -> HttpStatusCode.BadGateway to (message ?: "decode error")
    is MtprotoViewsClientError.NetworkError -> HttpStatusCode.BadGateway to (message ?: "network error")
    else -> HttpStatusCode.BadGateway to (message ?: "mtproto error")
}
