package routes

import analytics.AnalyticsPort
import auth.WebAppVerify
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import security.JwtSupport
import kotlin.collections.buildMap
import common.runCatchingNonFatal

fun Route.authRoutes(analytics: AnalyticsPort = AnalyticsPort.Noop) {
    post("/api/auth/telegram/verify") {
        val request = runCatchingNonFatal { call.receive<TelegramVerifyRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            return@post
        }

        val initData = request.initData?.takeIf { it.isNotBlank() }
        if (initData == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            return@post
        }

        val environment = call.application.environment
        val botToken = environment.config.property("telegram.botToken").getString()
        val ttlMinutes = environment.config.propertyOrNull("security.webappTtlMinutes")?.getString()?.toIntOrNull() ?: 15
        val now = Instant.now()

        val parsed = try {
            WebAppVerify.parse(initData)
        } catch (ex: IllegalArgumentException) {
            environment.log.warn("Telegram initData parsing failed: ${ex.message}")
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_init_data"))
            return@post
        }

        if (!WebAppVerify.isValid(parsed, botToken, ttlMinutes, now)) {
            environment.log.warn("Telegram initData validation failed")
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_init_data"))
            return@post
        }

        val userId = parsed.userId ?: run {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            return@post
        }

        val jwtConfig = JwtSupport.config(environment)
        val claims = buildMap<String, String> {
            parsed.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
            parsed.firstName?.takeIf { it.isNotBlank() }?.let { put("first_name", it) }
        }

        val token = JwtSupport.issueToken(jwtConfig, userId.toString(), claims, now)
        val expiresAt = now.plus(jwtConfig.accessTtlMinutes.toLong(), ChronoUnit.MINUTES)

        val response = TelegramVerifyResponse(
            token = token,
            expiresAt = expiresAt.toString(),
            user = TelegramUserPayload(
                id = userId,
                username = parsed.username,
                firstName = parsed.firstName,
            ),
        )

        call.respond(response)

        analytics.track(
            type = "miniapp_auth",
            userId = userId,
            source = "api",
            props = mapOf("auth" to "ok"),
        )
    }
}

@Serializable
private data class TelegramVerifyRequest(
    val initData: String? = null,
)

@Serializable
private data class TelegramVerifyResponse(
    val token: String,
    val expiresAt: String,
    val user: TelegramUserPayload,
)

@Serializable
private data class TelegramUserPayload(
    val id: Long,
    val username: String? = null,
    val firstName: String? = null,
)

@Serializable
private data class ErrorResponse(
    val error: String,
)
