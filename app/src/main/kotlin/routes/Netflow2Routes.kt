package routes

import alerts.INTERNAL_TOKEN_HEADER
import data.moex.Netflow2ClientError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import netflow2.Netflow2Loader
import netflow2.Netflow2LoaderError

fun Route.netflow2AdminRoutes(loader: Netflow2Loader, internalToken: String?) {
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

    post("/admin/netflow2/load") {
        if (!call.ensureInternalAccess()) return@post

        val sec = call.request.queryParameters["sec"]?.takeIf { it.isNotBlank() }
        val fromRaw = call.request.queryParameters["from"]
        val tillRaw = call.request.queryParameters["till"]

        if (sec == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sec is required"))
            return@post
        }
        val from = fromRaw?.toLocalDateOrNull()
        val till = tillRaw?.toLocalDateOrNull()

        if (from == null || till == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "from and till are required"))
            return@post
        }

        val result = try {
            loader.upsert(sec, from, till)
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            val (status, message) = ex.toHttpError()
            call.respond(status, mapOf("error" to message))
            return@post
        }

        call.respond(
            Netflow2LoadResponse(
                sec = result.sec,
                from = result.from.toString(),
                till = result.till.toString(),
                windows = result.windows,
                rows = result.rowsUpserted,
                maxDate = result.maxDate?.toString(),
            )
        )
    }
}

@Serializable
private data class Netflow2LoadResponse(
    val sec: String,
    val from: String,
    val till: String,
    val windows: Int,
    val rows: Int,
    val maxDate: String?
)

private fun Throwable.toHttpError(): Pair<HttpStatusCode, String> = when (this) {
    is Netflow2LoaderError.ValidationError -> HttpStatusCode.BadRequest to (message ?: "validation error")
    is Netflow2ClientError.NotFound -> HttpStatusCode.NotFound to (message ?: "sec not found")
    is Netflow2ClientError.ValidationError -> HttpStatusCode.BadRequest to (message ?: "validation error")
    is Netflow2ClientError.TimeoutError -> HttpStatusCode.GatewayTimeout to (message ?: "timeout")
    is Netflow2LoaderError.PullFailed -> cause?.toHttpError()
        ?: (HttpStatusCode.BadGateway to (message ?: "netflow2 pull failed"))
    else -> HttpStatusCode.BadGateway to (message ?: "netflow2 pull failed")
}

private fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(this)
} catch (ce: CancellationException) {
    throw ce
} catch (err: Error) {
    throw err
} catch (_: Throwable) {
    null
}
