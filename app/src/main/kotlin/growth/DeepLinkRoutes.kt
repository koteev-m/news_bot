package growth

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.slf4j.LoggerFactory

private const val METRIC_CTA_CLICK = "cta_click_total"
private const val METRIC_BOT_START = "bot_start_total"

/**
 * Установка роутов:
 * - GET /r/cta/{postId}?ab=A2&start=... -> 302 на t.me/<bot>?start=...
 * - GET /r/app?startapp=...             -> 302 на t.me/<bot>?startapp=...
 *
 * Метрики соответствуют PRD (Micrometer/Prometheus). Значение payload — канонический ID,
 * не сырой base64url.
 */
fun Application.installGrowthRoutes(meterRegistry: MeterRegistry) {
    val log = LoggerFactory.getLogger("growth.routes")

    val cfg = environment.config
    val bot = cfg.propertyOrNull("telegram.botUsername")?.getString()?.takeIf { it.isNotBlank() }
        ?: run {
            log.warn("telegram.botUsername is required; skipping growth routes")
            return
        }
    val limStart = cfg.propertyOrNull("growth.limits.start")?.getString()?.toIntOrNull() ?: 64
    val limStartApp = cfg.propertyOrNull("growth.limits.startapp")?.getString()?.toIntOrNull() ?: 512

    val registry = DeepLinkRegistry(limitStart = limStart, limitStartApp = limStartApp)

    routing {
        get("/r/cta/{postId}") {
            val postId = call.parameters["postId"].orEmpty()
            val ab = call.request.queryParameters["ab"].orEmpty()
            val parsed = registry.parseStart(call.request.queryParameters["start"].orEmpty())
            val payloadLabel = when (parsed) {
                null -> "INVALID"
                else -> parsed.canonicalId
            }

            // Метрика клика
            meterRegistry.counter(
                METRIC_CTA_CLICK,
                listOf(
                    Tag.of("post_id", postId),
                    Tag.of("ab", if (ab.isBlank()) "NA" else ab),
                    Tag.of("payload", payloadLabel),
                ),
            ).increment()

            val location = if (parsed == null) {
                "https://t.me/$bot"
            } else {
                "https://t.me/$bot?start=${parsed.raw}"
            }

            // Небольшая прозрачность окружения (без payload)
            val sourceIp = call.request.headers["X-Forwarded-For"]
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: call.request.headers["X-Real-IP"]?.takeIf { it.isNotEmpty() }
                ?: call.request.origin.remoteHost
            log.info(
                "cta_redirect post={} ab={} src={}",
                postId,
                if (ab.isBlank()) "NA" else ab,
                sourceIp,
            )

            call.response.header("Cache-Control", "no-store")
            call.respondRedirect(url = location, permanent = false)
        }

        get("/r/app") {
            val parsed = registry.parseStartApp(call.request.queryParameters["startapp"].orEmpty())

            if (parsed != null) {
                meterRegistry.counter(
                    METRIC_BOT_START,
                    listOf(
                        Tag.of("payload", parsed.canonicalId),
                    ),
                ).increment()
            }

            val location = if (parsed == null) {
                "https://t.me/$bot"
            } else {
                "https://t.me/$bot?startapp=${parsed.raw}"
            }

            val sourceIp = call.request.headers["X-Forwarded-For"]
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: call.request.headers["X-Real-IP"]?.takeIf { it.isNotEmpty() }
                ?: call.request.origin.remoteHost
            log.info("app_redirect src={}", sourceIp)
            call.response.header("Cache-Control", "no-store")
            call.respondRedirect(url = location, permanent = false)
        }
    }
}
