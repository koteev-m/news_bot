package observability

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import org.slf4j.MDC

fun Application.installSentry() {
    val cfg = environment.config
    val dsn = cfg.propertyOrNull("sentry.dsn")?.getString() ?: System.getenv("SENTRY_DSN")
    val env = cfg.propertyOrNull("sentry.environment")?.getString() ?: System.getenv("SENTRY_ENV") ?: "dev"
    val release = cfg.propertyOrNull("sentry.release")?.getString() ?: System.getenv("SENTRY_RELEASE")
    if (dsn.isNullOrBlank()) return

    Sentry.init { opts: SentryOptions ->
        opts.dsn = dsn
        opts.environment = env
        if (!release.isNullOrBlank()) opts.release = release
        opts.tracesSampleRate = 0.0
        opts.isSendDefaultPii = false
        opts.beforeSend =
            SentryOptions.BeforeSendCallback { event, _ ->
                val traceId = MDC.get("requestId")
                if (!traceId.isNullOrBlank()) {
                    event.setTag("traceId", traceId)
                    val existing = event.fingerprints
                    val updated =
                        buildList {
                            if (existing != null && existing.isNotEmpty()) {
                                addAll(existing)
                            } else {
                                add("event")
                            }
                            add(traceId)
                        }
                    event.setFingerprints(updated)
                }
                event.message?.let { message ->
                    val sanitized =
                        message.formatted
                            ?.replace(Regex("(?i)\\b(bearer\\s+[A-Za-z0-9._-]+)"), "***")
                            ?.replace(Regex("(?i)x-telegram-bot-api-secret-token:[^,\\s]+"), "***")
                            ?.replace(Regex("(?i)initData=[^&\\s]+"), "***")
                            ?.replace(Regex("token=[A-Za-z0-9:_-]{20,}"), "***")
                    if (sanitized != null) {
                        message.formatted = sanitized
                    }
                }
                event
            }
        opts.setDiagnosticLevel(SentryLevel.WARNING)
    }

    monitorSentryShutdown(environment)
}

private fun monitorSentryShutdown(environment: ApplicationEnvironment) {
    environment.monitor.subscribe(ApplicationStopping) {
        Sentry.flush(2000)
    }
    environment.monitor.subscribe(ApplicationStopped) {
        Sentry.close()
    }
}
