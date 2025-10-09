package app

import ab.ExperimentsPort
import ab.ExperimentsService
import ab.ExperimentsServiceImpl
import chaos.ChaosConfig
import chaos.ChaosMetrics
import chaos.maybeInjectChaos
import com.typesafe.config.ConfigFactory
import docs.apiDocsRoutes
import news.config.NewsConfig
import news.config.NewsDefaults
import repo.ExperimentsRepository
import repo.SupportRepository
import repo.ReferralsRepository
import referrals.ReferralsPort
import routes.adminExperimentsRoutes
import routes.adminSupportRoutes
import routes.experimentsRoutes
import pricing.PricingPortAdapter
import pricing.PricingService
import alerts.metrics.AlertMetricsPort
import analytics.AnalyticsPort
import errors.installErrorPages
import billing.StarsGatewayFactory
import billing.StarsWebhookHandler
import billing.TgUpdate
import billing.bot.StarsBotCommands
import billing.bot.StarsBotRouter
import billing.bot.StarsBotRouter.BotRoute
import billing.bot.StarsBotRouter.BotRoute.Buy
import billing.bot.StarsBotRouter.BotRoute.Callback
import billing.bot.StarsBotRouter.BotRoute.Plans
import billing.bot.StarsBotRouter.BotRoute.Status
import billing.bot.StarsBotRouter.BotRoute.Unknown
import billing.service.BillingService
import billing.service.BillingServiceImpl
import repo.BillingLedgerRepository
import billing.service.applySuccessfulPaymentOutcome
import com.pengrad.telegrambot.utility.BotUtils
import di.FeatureFlagsModule
import di.PrivacyModule
import di.ensureTelegramBot
import demo.demoRoutes
import di.installPortfolioModule
import features.FeatureFlagsService
import health.healthRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import news.metrics.NewsMetricsPort
import observability.DomainMetrics
import observability.Observability
import observability.WebVitals
import observability.WebhookMetrics
import observability.adapters.AlertMetricsAdapter
import observability.adapters.NewsMetricsAdapter
import observability.installMdcTrace
import observability.installSentry
import observability.installTracing
import org.slf4j.LoggerFactory
import repo.AnalyticsRepository
import repo.BillingRepositoryImpl
import repo.PricingRepository
import routes.BillingRouteServices
import routes.BillingRouteServicesKey
import routes.ChaosState
import routes.adminChaosRoutes
import routes.adminFeaturesRoutes
import routes.adminPrivacyRoutes
import routes.authRoutes
import routes.adminPricingRoutes
import routes.billingRoutes
import routes.portfolioImportRoutes
import routes.portfolioPositionsTradesRoutes
import routes.portfolioRoutes
import routes.portfolioValuationReportRoutes
import routes.quotesRoutes
import routes.redirectRoutes
import routes.supportRoutes
import routes.pricingRoutes
import routes.webVitalsRoutes
import security.installSecurity
import security.installUploadGuard
import security.RateLimitConfig
import security.SupportRateLimit
import java.time.Clock
import webhook.OverflowMode
import webhook.WebhookQueue

@Suppress("unused")
private val configuredCioWorkerThreads: Int = configureCioWorkerThreads()

fun Application.module() {
    val prometheusRegistry = Observability.install(this)
    installMdcTrace()
    installSentry()
    installTracing()
    val metrics = DomainMetrics(prometheusRegistry)
    val webhookMetrics = WebhookMetrics.create(prometheusRegistry)
    val vitals = WebVitals(prometheusRegistry)
    val appConfig = environment.config

    installSecurity()
    installUploadGuard()
    installErrorPages()
    installPortfolioModule()
    val newsConfig = loadNewsConfig()
    val analytics = AnalyticsRepository()
    val referrals = ReferralsRepository()
    val experimentsPort = ExperimentsRepository()
    val experimentsService = ExperimentsServiceImpl(experimentsPort)
    val featureFlags = FeatureFlagsModule.install(this)
    val services = ensureBillingServices(
        metrics = metrics,
        featureFlagsService = featureFlags.service,
        adminUserIds = featureFlags.adminUserIds,
        analyticsPort = analytics,
        referralsPort = referrals,
        experimentsPort = experimentsPort,
        experimentsService = experimentsService,
        newsConfig = newsConfig
    )
    val privacy = PrivacyModule.install(this, services.adminUserIds)
    val supportRepository = SupportRepository()
    val pricingRepository = PricingRepository()
    val pricingService = PricingService(PricingPortAdapter(pricingRepository))
    val supportRateLimitConfig = RateLimitConfig(
        capacity = appConfig.propertyOrNull("support.rateLimit.capacity")?.getString()?.toIntOrNull() ?: 5,
        refillPerMinute = appConfig.propertyOrNull("support.rateLimit.refillPerMinute")?.getString()?.toIntOrNull() ?: 5
    )
    val supportRateLimiter = SupportRateLimit.get(supportRateLimitConfig, Clock.systemUTC())

    val appProfile = (System.getenv("APP_PROFILE") ?: "dev").lowercase()
    val environmentAllowed = appProfile == "dev" || appProfile == "staging"
    val featuresChaos = appConfig.propertyOrNull("features.chaos")?.getString()?.toBoolean() ?: false
    val chaosEnabledConfig = appConfig.propertyOrNull("chaos.enabled")?.getString()?.toBoolean() ?: false
    val chaosConfig = ChaosConfig(
        enabled = chaosEnabledConfig,
        latencyMs = appConfig.propertyOrNull("chaos.latencyMs")?.getString()?.toLongOrNull() ?: 0L,
        jitterMs = appConfig.propertyOrNull("chaos.jitterMs")?.getString()?.toLongOrNull() ?: 0L,
        errorRate = appConfig.propertyOrNull("chaos.errorRate")?.getString()?.toDoubleOrNull() ?: 0.0,
        pathPrefix = appConfig.propertyOrNull("chaos.pathPrefix")?.getString() ?: "/api",
        method = appConfig.propertyOrNull("chaos.method")?.getString()?.ifBlank { "ANY" } ?: "ANY",
        percent = appConfig.propertyOrNull("chaos.percent")?.getString()?.toIntOrNull() ?: 100
    )
    val chaosState = ChaosState(
        featuresEnabled = featuresChaos,
        environmentAllowed = environmentAllowed,
        initial = chaosConfig
    )
    val chaosMetrics = ChaosMetrics(prometheusRegistry)

    val queueConfig = webhookQueueConfig()
    val webhookQueue = WebhookQueue(
        capacity = queueConfig.capacity,
        mode = queueConfig.mode,
        workers = queueConfig.workers,
        scope = this,
        metrics = webhookMetrics
    ) { update ->
        processStarsPayment(update, services.billingService, metrics, analytics)
    }
    webhookQueue.start()

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path == "/healthz" || path.startsWith("/metrics") || path.startsWith("/api/admin/chaos")) {
            return@intercept
        }
        if (maybeInjectChaos(call, chaosState.cfg, chaosMetrics)) {
            finish()
        }
    }

    environment.monitor.subscribe(ApplicationStopped) {
        runBlocking {
            webhookQueue.shutdown(queueConfig.shutdownTimeout)
        }
    }

    routing {
        webVitalsRoutes(vitals)
        apiDocsRoutes()
        healthRoutes()
        authRoutes(analytics)
        redirectRoutes(analytics, referrals, newsConfig.botDeepLinkBase, newsConfig.maxPayloadBytes)
        supportRoutes(supportRepository, services.analytics, supportRateLimiter)
        pricingRoutes(experimentsService, pricingService, services.analytics)
        experimentsRoutes(experimentsService)
        quotesRoutes()
        demoRoutes()

        post("/telegram/webhook") {
            val expectedSecret = environment.config.propertyOrNull("telegram.webhookSecret")?.getString()
            val providedSecret = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
            if (expectedSecret.isNullOrBlank() || providedSecret != expectedSecret) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

            val rawUpdate = runCatching { call.receiveText() }.getOrElse {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val tgUpdate = runCatching { StarsWebhookHandler.json.decodeFromString<TgUpdate>(rawUpdate) }.getOrElse {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            webhookQueue.offer(tgUpdate)
            call.respond(HttpStatusCode.OK)

            val servicesAttr = this@module.attributes[Services.Key]
            val botUpdate = runCatching { BotUtils.parseUpdate(rawUpdate) }.getOrNull()
            if (botUpdate == null) {
                return@post
            }

            val bot = servicesAttr.telegramBot
            val billingSvc = servicesAttr.billingService
            val route = StarsBotRouter.route(botUpdate)
            if (route == Unknown) {
                return@post
            }

            launch {
                when (route) {
                    Plans -> StarsBotCommands.handlePlans(botUpdate, bot, billingSvc)
                    Buy -> StarsBotCommands.handleBuy(botUpdate, bot, billingSvc)
                    Status -> StarsBotCommands.handleStatus(botUpdate, bot, billingSvc)
                    Callback -> StarsBotCommands.handleCallback(botUpdate, bot, billingSvc)
                    Unknown -> Unit
                }
            }
        }

        authenticate("auth-jwt") {
            portfolioRoutes()
            portfolioPositionsTradesRoutes()
            portfolioImportRoutes()
            portfolioValuationReportRoutes()
            billingRoutes()
            adminExperimentsRoutes(experimentsPort, services.adminUserIds)
            adminFeaturesRoutes()
            adminChaosRoutes(chaosState, services.adminUserIds)
            adminPrivacyRoutes(privacy.service, services.adminUserIds)
            adminPricingRoutes(pricingRepository, services.adminUserIds)
            adminSupportRoutes(supportRepository, services.adminUserIds)
        }
    }
}

private fun configureCioWorkerThreads(): Int {
    val existing = System.getProperty("ktor.server.cio.workerCount")?.toIntOrNull()
    if (existing != null && existing > 0) {
        return existing
    }
    val config = ConfigFactory.load()
    val fromConfig = runCatching { config.getInt("performance.workerThreads") }.getOrNull()
    val resolved = (fromConfig ?: Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    System.setProperty("ktor.server.cio.workerCount", resolved.toString())
    return resolved
}

private fun Application.ensureBillingServices(
    metrics: DomainMetrics,
    featureFlagsService: FeatureFlagsService,
    adminUserIds: Set<Long>,
    analyticsPort: AnalyticsPort,
    referralsPort: ReferralsPort,
    experimentsPort: ExperimentsPort,
    experimentsService: ExperimentsService,
    newsConfig: NewsConfig
): Services {
    if (attributes.contains(Services.Key)) {
        val existing = attributes[Services.Key]
        val enriched = existing.enrich(metrics, featureFlagsService, adminUserIds)
        val updated = enriched.copy(
            analytics = analyticsPort,
            referrals = referralsPort,
            experimentsPort = experimentsPort,
            experimentsService = experimentsService,
            newsConfig = newsConfig
        )
        attributes.put(Services.Key, updated)
        attributes.put(BillingRouteServicesKey, BillingRouteServices(updated.billingService))
        return updated
    }

    val telegramBot = ensureTelegramBot()

    val billingService = BillingServiceImpl(
        repo = BillingRepositoryImpl(),
        stars = StarsGatewayFactory.fromConfig(environment),
        ledger = BillingLedgerRepository(),
        defaultDurationDays = billingDefaultDuration(),
    )
    val services = Services(
        billingService = billingService,
        telegramBot = telegramBot,
        featureFlags = featureFlagsService,
        adminUserIds = adminUserIds,
        metrics = metrics,
        alertMetrics = AlertMetricsAdapter(metrics),
        newsMetrics = NewsMetricsAdapter(metrics),
        analytics = analyticsPort,
        referrals = referralsPort,
        experimentsPort = experimentsPort,
        experimentsService = experimentsService,
        newsConfig = newsConfig
    )
    attributes.put(Services.Key, services)
    attributes.put(BillingRouteServicesKey, BillingRouteServices(billingService))
    return services
}

private fun Services.enrich(
    metrics: DomainMetrics,
    featureFlagsService: FeatureFlagsService,
    adminUserIds: Set<Long>
): Services {
    return copy(
        featureFlags = featureFlagsService,
        adminUserIds = adminUserIds,
        metrics = metrics,
        alertMetrics = alertMetrics ?: AlertMetricsAdapter(metrics),
        newsMetrics = newsMetrics ?: NewsMetricsAdapter(metrics)
    )
}

private fun Application.loadNewsConfig(): NewsConfig {
    val defaults = NewsDefaults.defaultConfig
    val config = environment.config
    val baseFromConfig = config.propertyOrNull("news.botDeepLinkBase")?.getString()?.trim()
    val baseFromTelegram = config.propertyOrNull("telegram.botUsername")?.getString()?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://t.me/${it}" }
    val botBase = baseFromConfig?.takeIf { it.isNotEmpty() } ?: baseFromTelegram ?: defaults.botDeepLinkBase
    val maxBytes = config.propertyOrNull("news.maxPayloadBytes")?.getString()?.toIntOrNull() ?: defaults.maxPayloadBytes
    val channelId = config.propertyOrNull("news.channelId")?.getString()?.toLongOrNull()
        ?: config.propertyOrNull("telegram.channelId")?.getString()?.toLongOrNull()
        ?: defaults.channelId
    return defaults.copy(botDeepLinkBase = botBase, maxPayloadBytes = maxBytes, channelId = channelId)
}

private fun Application.billingDefaultDuration(): Long {
    val raw = environment.config.propertyOrNull("billing.defaultDurationDays")?.getString()
    return raw?.toLongOrNull() ?: 30L
}

private data class WebhookQueueConfig(
    val capacity: Int,
    val workers: Int,
    val mode: OverflowMode,
    val shutdownTimeout: Duration
)

private fun Application.webhookQueueConfig(): WebhookQueueConfig {
    val config = environment.config
    val capacity = config.propertyOrNull("webhook.queue.capacity")?.getString()?.toIntOrNull() ?: 1000
    val workers = config.propertyOrNull("webhook.queue.workers")?.getString()?.toIntOrNull() ?: 4
    val overflowRaw = config.propertyOrNull("webhook.queue.overflow")?.getString().orEmpty()
    val mode = OverflowMode.entries.firstOrNull { it.name.equals(overflowRaw, ignoreCase = true) }
        ?: OverflowMode.DROP_OLDEST
    return WebhookQueueConfig(
        capacity = capacity,
        workers = workers,
        mode = mode,
        shutdownTimeout = 5.seconds
    )
}

private val webhookLogger = LoggerFactory.getLogger("WebhookProcessor")

private suspend fun processStarsPayment(
    update: TgUpdate,
    billing: BillingService,
    metrics: DomainMetrics,
    analytics: AnalyticsPort
) {
    val successfulPayment = update.message?.successful_payment ?: return
    if (!successfulPayment.currency.equals("XTR", ignoreCase = true)) {
        return
    }
    val userId = update.message?.from?.id ?: return
    val payloadData = parsePayload(successfulPayment.invoice_payload) ?: return
    val (payloadUserId, tier) = payloadData
    if (payloadUserId != userId) {
        webhookLogger.info("webhook-queue reason=user_mismatch")
        return
    }

    val outcomeResult = billing.applySuccessfulPaymentOutcome(
        userId = userId,
        tier = tier,
        amountXtr = successfulPayment.total_amount,
        providerPaymentId = successfulPayment.provider_payment_charge_id,
        payload = successfulPayment.invoice_payload
    )

    outcomeResult.fold(
        onSuccess = { outcome ->
            if (outcome.duplicate) {
                analytics.track(
                    type = "stars_payment_duplicate",
                    userId = userId,
                    source = "webhook",
                    props = mapOf("tier" to tier.name)
                )
                metrics.webhookStarsDuplicate.increment()
                webhookLogger.info("webhook-queue reason=duplicate")
            } else {
                analytics.track(
                    type = "stars_payment_succeeded",
                    userId = userId,
                    source = "webhook",
                    props = mapOf("tier" to tier.name)
                )
                metrics.webhookStarsSuccess.increment()
                webhookLogger.info("webhook-queue reason=applied_ok")
            }
        },
        onFailure = { error ->
            webhookLogger.error("webhook-queue reason=apply_failed", error)
        }
    )
}

private fun parsePayload(payload: String?): Pair<Long, billing.model.Tier>? {
    if (payload.isNullOrBlank()) return null
    val parts = payload.split(':')
    if (parts.size < 3) return null
    val userId = parts[0].toLongOrNull() ?: return null
    val tier = runCatching { billing.model.Tier.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
    return userId to tier
}

data class Services(
    val billingService: BillingService,
    val telegramBot: com.pengrad.telegrambot.TelegramBot,
    val featureFlags: FeatureFlagsService,
    val adminUserIds: Set<Long>,
    val metrics: DomainMetrics? = null,
    val alertMetrics: AlertMetricsPort? = null,
    val newsMetrics: NewsMetricsPort? = null,
    val analytics: AnalyticsPort = AnalyticsPort.Noop,
    val referrals: ReferralsPort? = null,
    val experimentsPort: ExperimentsPort? = null,
    val experimentsService: ExperimentsService? = null,
    val newsConfig: NewsConfig? = null,
) {
    companion object {
        val Key: AttributeKey<Services> = AttributeKey("AppServices")
    }
}
