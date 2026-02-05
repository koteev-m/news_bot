package routes

import analytics.AnalyticsPort
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import repo.FaqItem
import repo.SupportRepository
import repo.SupportTicket
import security.RateLimiter
import security.userIdOrNull

private val allowedTicketStatuses = setOf("OPEN", "ACK", "RESOLVED", "REJECTED")

@Serializable
data class FeedbackReq(
    val category: String,
    val subject: String,
    val message: String,
    val appVersion: String? = null,
    val deviceInfo: String? = null,
    val locale: String? = null,
)

@Serializable
private data class FaqResponse(
    val locale: String,
    val slug: String,
    val title: String,
    val bodyMd: String,
    val updatedAt: String,
)

@Serializable
private data class TicketResponse(
    val ticketId: Long,
    val ts: String,
    val userId: Long?,
    val category: String,
    val locale: String,
    val subject: String,
    val message: String,
    val status: String,
    val appVersion: String?,
    val deviceInfo: String?,
)

@Serializable
data class TicketStatusReq(
    val status: String,
)

fun Route.supportRoutes(
    repo: SupportRepository,
    analytics: AnalyticsPort,
    rateLimiter: RateLimiter,
) {
    route("/api/support") {
        get("/faq/{locale}") {
            val locale = call.parameters["locale"].orEmpty().ifBlank { "en" }
            val list = repo.listFaq(locale)
            val payload = list.map { it.toResponse() }
            call.respond(HttpStatusCode.OK, payload)
        }
        post("/feedback") {
            val rateSubject =
                call.userIdOrNull
                    ?: call.request
                        .header(
                            HttpHeaders.XForwardedFor,
                        )?.split(',')
                        ?.firstOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    ?: call.request.header("X-Real-IP")?.takeIf { it.isNotBlank() }
                    ?: call.request.host().takeIf { it.isNotBlank() }
                    ?: "anonymous"
            val (allowed, retryAfter) = rateLimiter.tryAcquire(rateSubject)
            if (!allowed) {
                call.respondTooManyRequests(retryAfter ?: DEFAULT_RETRY_AFTER)
                return@post
            }

            val req = call.receive<FeedbackReq>()
            val userId = call.userIdOrNull?.toLongOrNull()
            val locale =
                req.locale
                    ?.lowercase()
                    ?.take(LOCALE_MAX_LENGTH)
                    .orEmpty()
                    .ifBlank { "en" }
            val category =
                req.category
                    .trim()
                    .lowercase()
                    .take(CATEGORY_MAX_LENGTH)
                    .ifBlank { "idea" }
            val subject = req.subject.trim().take(SUBJECT_MAX_LENGTH)
            val message = req.message.trim().take(MESSAGE_MAX_LENGTH)
            val appVersion = req.appVersion?.trim()?.take(APP_VERSION_MAX_LENGTH)
            val deviceInfo = req.deviceInfo?.trim()?.take(DEVICE_INFO_MAX_LENGTH)

            if (subject.isBlank()) {
                call.respondBadRequest(listOf("subject"))
                return@post
            }
            if (message.isBlank()) {
                call.respondBadRequest(listOf("message"))
                return@post
            }

            val ticketId =
                repo.createTicket(
                    SupportTicket(
                        userId = userId,
                        category = category,
                        locale = locale,
                        subject = subject,
                        message = message,
                        status = "OPEN",
                        appVersion = appVersion,
                        deviceInfo = deviceInfo,
                    ),
                )

            analytics.track(
                type = "support_feedback_submitted",
                userId = userId,
                source = "api",
                props =
                mapOf(
                    "ticket_id" to ticketId,
                    "category" to category,
                    "locale" to locale,
                ),
            )
            call.respond(HttpStatusCode.Accepted, mapOf("ticketId" to ticketId))
        }
    }
}

fun Route.adminSupportRoutes(
    repo: SupportRepository,
    adminUserIds: Set<Long>,
) {
    route("/api/admin/support") {
        get("/tickets") {
            val uid =
                call.userIdOrNull?.toLongOrNull() ?: run {
                    call.respondUnauthorized()
                    return@get
                }
            if (uid !in adminUserIds) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@get
            }
            val statusParam =
                call.request.queryParameters["status"]
                    ?.takeIf { it.isNotBlank() }
                    ?.trim()
                    ?.uppercase()
            if (statusParam != null && statusParam !in allowedTicketStatuses) {
                call.respondBadRequest(listOf("status"))
                return@get
            }
            val limit =
                call.request.queryParameters["limit"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
            val tickets = repo.listTickets(statusParam, limit)
            val payload = tickets.mapNotNull { it.toResponseOrNull() }
            call.respond(HttpStatusCode.OK, payload)
        }
        patch("/tickets/{id}/status") {
            val uid =
                call.userIdOrNull?.toLongOrNull() ?: run {
                    call.respondUnauthorized()
                    return@patch
                }
            if (uid !in adminUserIds) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@patch
            }
            val id =
                call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respondBadRequest(listOf("id"))
                    return@patch
                }
            val body = call.receive<TicketStatusReq>()
            val nextStatus = body.status.trim().uppercase()
            if (nextStatus !in allowedTicketStatuses) {
                call.respondBadRequest(listOf("status"))
                return@patch
            }
            repo.updateStatus(id, nextStatus)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun FaqItem.toResponse(): FaqResponse =
    FaqResponse(
        locale = locale,
        slug = slug,
        title = title,
        bodyMd = bodyMd,
        updatedAt = updatedAt.toString(),
    )

private fun SupportTicket.toResponseOrNull(): TicketResponse? {
    val identifier = ticketId ?: return null
    val timestamp = ts?.toString() ?: return null
    return TicketResponse(
        ticketId = identifier,
        ts = timestamp,
        userId = userId,
        category = category,
        locale = locale,
        subject = subject,
        message = message,
        status = status,
        appVersion = appVersion,
        deviceInfo = deviceInfo,
    )
}

private const val DEFAULT_RETRY_AFTER = 60
private const val LOCALE_MAX_LENGTH = 8
private const val CATEGORY_MAX_LENGTH = 32
private const val SUBJECT_MAX_LENGTH = 120
private const val MESSAGE_MAX_LENGTH = 4000
private const val APP_VERSION_MAX_LENGTH = 64
private const val DEVICE_INFO_MAX_LENGTH = 256
private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 1000
