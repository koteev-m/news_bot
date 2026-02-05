package growth

import db.DatabaseFactory
import db.tables.CtaClicksTable
import deeplink.DeepLinkPayload
import deeplink.DeepLinkStore
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.insert
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val METRIC_CTA_CLICK = "cta_click_total"
private const val METRIC_BOT_START = "bot_start_total"
internal const val USER_AGENT_MAX_LEN = 512

/**
 * Установка роутов:
 * - GET /r/cta/{postId}?ab=A2&start=... -> 302 на t.me/<bot>?start=...
 * - GET /r/app?startapp=...             -> 302 на t.me/<bot>?startapp=...
 *
 * Метрики соответствуют PRD (Micrometer/Prometheus). Значение payload — канонический ID,
 * не сырой base64url.
 */
fun Application.installGrowthRoutes(
    meterRegistry: MeterRegistry,
    deepLinkStore: DeepLinkStore,
) {
    val log = LoggerFactory.getLogger("growth.routes")

    val cfg = environment.config
    val bot =
        cfg.propertyOrNull("telegram.botUsername")?.getString()?.takeIf { it.isNotBlank() }
            ?: run {
                log.warn("telegram.botUsername is required; skipping growth routes")
                return
            }
    val limStart = cfg.propertyOrNull("growth.limits.start")?.getString()?.toIntOrNull() ?: 12
    val limStartApp = cfg.propertyOrNull("growth.limits.startapp")?.getString()?.toIntOrNull() ?: 12

    val registry = DeepLinkRegistry(limitStart = limStart, limitStartApp = limStartApp)

    routing {
        get("/r/cta/{postId}") {
            val postId = call.parameters["postId"].orEmpty()
            val ab = call.request.queryParameters["ab"].orEmpty()
            val parsed = registry.parseStart(call.request.queryParameters["start"].orEmpty())
            val payloadLabel =
                resolvePayloadMetricLabel(
                    parsed = parsed,
                    deepLinkStore = deepLinkStore,
                    log = log,
                    context = "cta redirect",
                )
            val abLabel = normalizeAbVariant(ab)

            // Метрика клика
            meterRegistry
                .counter(
                    METRIC_CTA_CLICK,
                    listOf(
                        Tag.of("ab", abLabel),
                        Tag.of("payload", payloadLabel),
                    ),
                ).increment()

            recordCtaClick(
                log = log,
                postId = postId,
                abVariant = abLabel,
                userAgent = call.request.headers["User-Agent"],
            )

            val location =
                if (parsed == null) {
                    "https://t.me/$bot"
                } else {
                    "https://t.me/$bot?start=${parsed.raw}"
                }

            // Небольшая прозрачность окружения (без payload)
            val sourceIp =
                call.request.headers["X-Forwarded-For"]
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: call.request.headers["X-Real-IP"]?.takeIf { it.isNotEmpty() }
                    ?: call.request.origin.remoteHost
            log.info(
                "cta_redirect post={} ab={} src={}",
                postId,
                abLabel,
                sourceIp,
            )

            call.response.header("Cache-Control", "no-store")
            call.respondRedirect(url = location, permanent = false)
        }

        get("/r/app") {
            val parsed = registry.parseStartApp(call.request.queryParameters["startapp"].orEmpty())

            if (parsed != null) {
                val payloadLabel =
                    resolvePayloadMetricLabel(
                        parsed = parsed,
                        deepLinkStore = deepLinkStore,
                        log = log,
                        context = "app redirect",
                    )
                meterRegistry
                    .counter(
                        METRIC_BOT_START,
                        listOf(
                            Tag.of("payload", payloadLabel),
                        ),
                    ).increment()
            }

            val location =
                if (parsed == null) {
                    "https://t.me/$bot"
                } else {
                    "https://t.me/$bot?startapp=${parsed.raw}"
                }

            val sourceIp =
                call.request.headers["X-Forwarded-For"]
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

private suspend fun resolvePayloadMetricLabel(
    parsed: DeepLinkRegistry.Parsed?,
    deepLinkStore: DeepLinkStore,
    log: org.slf4j.Logger,
    context: String,
): String {
    if (parsed == null) {
        return PayloadMetricLabel.INVALID.value
    }
    return try {
        val payload = withContext(Dispatchers.IO) { deepLinkStore.get(parsed.raw) }
        payload?.toMetricLabel() ?: PayloadMetricLabel.UNKNOWN.value
    } catch (e: TimeoutCancellationException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("deeplink store get failed for {}", context, e)
        PayloadMetricLabel.STORE_ERROR.value
    }
}

private fun DeepLinkPayload.toMetricLabel(): String =
    when (type) {
        deeplink.DeepLinkType.TICKER -> PayloadMetricLabel.TICKER.value
        deeplink.DeepLinkType.TOPIC -> PayloadMetricLabel.TOPIC.value
        deeplink.DeepLinkType.PORTFOLIO -> PayloadMetricLabel.PORTFOLIO.value
    }

internal fun normalizeAbVariant(raw: String): String {
    if (raw.isBlank()) {
        return "NA"
    }
    return when {
        raw.trim().startsWith("A", ignoreCase = true) -> "A"
        raw.trim().startsWith("B", ignoreCase = true) -> "B"
        else -> "NA"
    }
}

internal fun normalizeUserAgent(userAgent: String?): String? {
    val normalized = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return normalized.take(USER_AGENT_MAX_LEN)
}

internal fun parsePostId(postId: String): Long? = postId.toLongOrNull()?.takeIf { it > 0 }

private suspend fun recordCtaClick(
    log: org.slf4j.Logger,
    postId: String,
    abVariant: String,
    userAgent: String?,
) {
    if (!DatabaseFactory.isInitialized()) {
        log.debug("cta_clicks insert skipped: DatabaseFactory not initialized")
        return
    }
    val normalizedPostId = parsePostId(postId)
    if (normalizedPostId == null) {
        log.debug("cta_clicks insert skipped: invalid postId")
        return
    }
    val normalizedUserAgent = normalizeUserAgent(userAgent)
    val now = Instant.now().atOffset(ZoneOffset.UTC)
    val redirectId = UUID.randomUUID()
    try {
        DatabaseFactory.dbQuery {
            CtaClicksTable.insert {
                it[CtaClicksTable.postId] = normalizedPostId
                it[CtaClicksTable.clusterId] = null
                it[CtaClicksTable.variant] = abVariant
                it[CtaClicksTable.redirectId] = redirectId
                it[CtaClicksTable.clickedAt] = now
                it[CtaClicksTable.userAgent] = normalizedUserAgent
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("cta_clicks insert failed", e)
    }
}

private enum class PayloadMetricLabel(
    val value: String,
) {
    PORTFOLIO("PORTFOLIO"),
    TICKER("TICKER"),
    TOPIC("TOPIC"),
    UNKNOWN("UNKNOWN"),
    INVALID("INVALID"),
    STORE_ERROR("STORE_ERROR"),
}
