package security

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.ResponseHeaders

private const val METRICS_PATH = "/metrics"
private const val DB_HEALTH_PATH = "/health/db"

fun Application.installSecurityHeaders() {
    install(SecurityHeadersPlugin)
}

private val SecurityHeadersPlugin = createApplicationPlugin("SecurityHeaders") {
    val isProd = System.getenv("APP_PROFILE")?.trim()?.equals("prod", ignoreCase = true) == true

    onCall { call ->
        call.response.pipeline.intercept(ApplicationSendPipeline.After) {
            val path = call.request.path()
            if (path == METRICS_PATH || path == DB_HEALTH_PATH) {
                proceed()
                return@intercept
            }

            val headers = call.response.headers

            headers.appendIfAbsent("X-Content-Type-Options", "nosniff")
            headers.appendIfAbsent("X-Frame-Options", "DENY")
            headers.appendIfAbsent("Referrer-Policy", "no-referrer")
            headers.appendIfAbsent("Permissions-Policy", "camera=(), microphone=(), geolocation=()")

            val contentTypeHeader = headers[HttpHeaders.ContentType]
            val isHtml = contentTypeHeader?.let { header ->
                header.substringBefore(';').trim().equals(ContentType.Text.Html.toString(), ignoreCase = true)
            } == true

            if (isHtml) {
                headers.appendIfAbsent("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
                if (isProd) {
                    headers.appendIfAbsent("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
                }
            }

            proceed()
        }
    }
}

private fun ResponseHeaders.appendIfAbsent(name: String, value: String) {
    if (this[name] == null) {
        append(name, value)
    }
}
