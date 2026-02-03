package app

import ab.ExperimentsPort
import ab.ExperimentsService
import ab.ExperimentsServiceImpl
import chaos.ChaosConfig
import chaos.ChaosMetrics
import chaos.maybeInjectChaos
import db.DatabaseFactory
import docs.apiDocsRoutes
import news.config.NewsConfig
import news.config.NewsDefaults
import news.config.NewsMode
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
import alerts.AlertsRepository
import alerts.AlertsRepositoryMemory
import alerts.AlertsRepositoryPostgres
import alerts.AlertsService
import alerts.alertsRoutes
import alerts.loadAlertsConfig
import io.ktor.client.HttpClient
import errors.installErrorPages
import billing.StarsGatewayFactory
import billing.StarsWebhookHandler
import billing.TgUpdate
import growth.installGrowthRoutes
import billing.bot.StarsBotCommands
import billing.bot.StarsBotRouter
import billing.bot.StarsBotRouter.BotRoute.Buy
import billing.bot.StarsBotRouter.BotRoute.Callback
import billing.bot.StarsBotRouter.BotRoute.Plans
import billing.bot.StarsBotRouter.BotRoute.Status
import billing.bot.StarsBotRouter.BotRoute.Unknown
import billing.service.BillingService
import billing.service.BillingServiceImpl
import billing.service.EntitlementsService
import repo.BillingLedgerRepository
import billing.service.applySuccessfulPaymentOutcome
import billing.stars.BotBalanceRateLimiter
import billing.stars.BotStarBalancePort
import billing.stars.StarsClient
import billing.stars.StarsClientConfig
import billing.stars.StarsService
import billing.stars.ZeroStarBalancePort
import com.pengrad.telegrambot.utility.BotUtils
import deeplink.DeepLinkStore
import deeplink.createDeepLinkStore
import deeplink.deepLinkTtl
import deeplink.loadDeepLinkStoreSettings
import di.FeatureFlagsModule
import di.PrivacyModule
import di.ensureTelegramBot
import demo.demoRoutes
import di.installPortfolioModule
import integrations.integrationsModule
import integrations.mtprotoViewsConfig
import features.FeatureFlagsService
import health.healthRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStopped
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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.decodeFromString
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import news.metrics.NewsMetricsPort
import news.dedup.Clusterer
import news.pipeline.DbPublishJobRepository
import news.pipeline.DigestPublishWorker
import news.pipeline.DigestSlotScheduler
import news.pipeline.NewsPipeline
import news.pipeline.PublishJobQueue
import news.publisher.PengradTelegramClient
import news.publisher.TelegramPublisher
import news.publisher.store.DbIdempotencyStore
import news.publisher.store.DbPostStatsStore
import news.rss.RssFetcher
import news.sources.CbrSource
import news.sources.MoexSource
import news.moderation.ModerationBotConfig
import news.moderation.ModerationBotHandler
import news.moderation.ModerationQueue
import news.moderation.ModerationQueueDatabase
import news.moderation.ModerationRepository
import observability.DomainMetrics
import observability.EventsCounter
import observability.Observability
import observability.WebVitals
import observability.WebhookMetrics
import observability.adapters.AlertMetricsAdapter
import observability.adapters.NewsMetricsAdapter
import observability.feed.Netflow2Metrics
import observability.installMdcTrace
import observability.installSentry
import observability.installTracing
import org.slf4j.LoggerFactory
import io.ktor.server.config.ApplicationConfig
import repo.AnalyticsRepository
import repo.BillingRepositoryImpl
import repo.PricingRepository
import repo.PostgresNetflow2Repository
import routes.BillingRouteServices
import routes.BillingRouteServicesKey
import routes.ChaosState
import routes.adminChaosRoutes
import routes.adminFeaturesRoutes
import routes.adminPrivacyRoutes
import routes.authRoutes
import routes.adminPricingRoutes
import routes.billingRoutes
import routes.netflow2AdminRoutes
import routes.postViewsRoutes
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
import io.micrometer.core.instrument.Timer
import netflow2.Netflow2Loader
import mtproto.HttpMtprotoViewsClient
import webhook.OverflowMode
import webhook.WebhookQueue
import common.runCatchingNonFatal
import views.PostViewsService
import org.jetbrains.exposed.sql.update

fun Application.module() {
    ensureCioWorkerThreadsConfigured()
    val appConfig = environment.config
    val prometheusRegistry = Observability.install(this)
    val eventsCounter = EventsCounter(prometheusRegistry)
    installMdcTrace()
    installSentry()
    installTracing()
    val metrics = DomainMetrics(prometheusRegistry)
    val webhookMetrics = WebhookMetrics.create(prometheusRegistry)
    val vitals = WebVitals(prometheusRegistry)
    val alertsConfig = loadAlertsConfig(appConfig)
    val alertsRepository = createAlertsRepository(appConfig)
    val alertsService = AlertsService(alertsRepository, alertsConfig.engine, prometheusRegistry)
    val webhookConfig = TelegramWebhookConfig.from(appConfig)
    validateTelegramWebhookConfig(webhookConfig)
    val appProfile = (System.getenv("APP_PROFILE") ?: "dev").lowercase()
    val deepLinkLog = LoggerFactory.getLogger("deeplink")
    val deepLinkStoreSettings = loadDeepLinkStoreSettings(appConfig, deepLinkLog)
    val deepLinkStore = createDeepLinkStore(deepLinkStoreSettings, appProfile, deepLinkLog)
    val deepLinkTtl = deepLinkTtl(deepLinkStoreSettings)
    val integrations = integrationsModule()
    val netflow2Metrics = Netflow2Metrics(prometheusRegistry)
    val netflow2Repository = PostgresNetflow2Repository()
    val netflow2Loader = Netflow2Loader(integrations.netflow2Client, netflow2Repository, netflow2Metrics)
    val mtprotoConfig = environment.mtprotoViewsConfig()
    val mtprotoEnabled = mtprotoConfig.enabled
    val postViewsService = if (mtprotoEnabled) {
        val baseUrl = requireNotNull(mtprotoConfig.baseUrl) { "mtproto baseUrl must be set when enabled" }
        PostViewsService(
            client = HttpMtprotoViewsClient(
                client = integrations.httpClient,
                baseUrl = baseUrl,
                apiKey = mtprotoConfig.apiKey
            ),
            registry = prometheusRegistry
        )
    } else {
        null
    }

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
        newsConfig = newsConfig,
        deepLinkStore = deepLinkStore,
        deepLinkTtl = deepLinkTtl,
    )
    val publishJobQueue = DbPublishJobRepository()
    val digestScheduler = DigestSlotScheduler(
        slots = newsConfig.antiNoise.digestSlots,
        fallbackIntervalSeconds = newsConfig.digestMinIntervalSeconds,
    )
    val moderationConfig = loadModerationBotConfig(newsConfig)
    val moderationRepository = moderationConfig?.let { ModerationRepository() }
    val moderationQueue: ModerationQueue = if (moderationConfig != null && moderationRepository != null) {
        ModerationQueueDatabase(
            repository = moderationRepository,
            bot = services.telegramBot,
            config = moderationConfig
        )
    } else {
        ModerationQueue.Noop
    }
    val moderationHandler = if (moderationConfig != null && moderationRepository != null) {
        ModerationBotHandler(
            bot = services.telegramBot,
            repository = moderationRepository,
            newsConfig = newsConfig,
            config = moderationConfig,
            publishJobQueue = publishJobQueue,
            digestScheduler = digestScheduler,
        )
    } else {
        null
    }
    val newsScheduler = startNewsPipelineScheduler(
        newsConfig = newsConfig,
        services = services,
        metrics = metrics,
        config = appConfig,
        deepLinkStore = services.deepLinkStore,
        deepLinkTtl = services.deepLinkTtl,
        moderationQueue = moderationQueue,
        publishJobQueue = publishJobQueue,
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
        processStarsPayment(update, services.billingService, metrics, analytics, eventsCounter)
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
    environment.monitor.subscribe(ApplicationStopped) {
        runBlocking {
            newsScheduler.stop()
        }
    }
    environment.monitor.subscribe(ApplicationStopped) {
        (deepLinkStore as? AutoCloseable)?.close()
    }

    installGrowthRoutes(prometheusRegistry, services.deepLinkStore)

    routing {
        webVitalsRoutes(vitals)
        alertsRoutes(alertsService, alertsConfig.internalToken)
        netflow2AdminRoutes(netflow2Loader, alertsConfig.internalToken)
        postViewsRoutes(postViewsService, mtprotoEnabled, alertsConfig.internalToken)
        apiDocsRoutes()
        healthRoutes()
        authRoutes(analytics)
        redirectRoutes(
            analytics,
            referrals,
            newsConfig.botDeepLinkBase,
            services.deepLinkStore,
            services.deepLinkTtl,
        )
        supportRoutes(supportRepository, services.analytics, supportRateLimiter)
        pricingRoutes(experimentsService, pricingService, services.analytics, eventsCounter)
        experimentsRoutes(experimentsService)
        quotesRoutes()
        demoRoutes()

        post("/telegram/webhook") {
            val expectedSecret = webhookConfig.secret
            val providedSecret = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
            if (expectedSecret.isNullOrBlank() || providedSecret != expectedSecret) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

            val rawUpdate = runCatchingNonFatal { call.receiveText() }.getOrElse {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val tgUpdate = runCatchingNonFatal {
                StarsWebhookHandler.json.decodeFromString<TgUpdate>(
                    rawUpdate
                )
            }.getOrElse {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            webhookQueue.offer(tgUpdate)
            call.respond(HttpStatusCode.OK)

            val servicesAttr = this@module.attributes[Services.Key]
            val botUpdate = runCatchingNonFatal { BotUtils.parseUpdate(rawUpdate) }.getOrNull()
            if (botUpdate == null) {
                return@post
            }

            val bot = servicesAttr.telegramBot
            val billingSvc = servicesAttr.billingService
            val route = StarsBotRouter.route(botUpdate)
            if (moderationHandler != null && moderationHandler.handleUpdate(botUpdate)) {
                return@post
            }
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

private fun Application.createAlertsRepository(config: ApplicationConfig): AlertsRepository {
    val logger = LoggerFactory.getLogger("alerts")
    val dbJdbcUrl = config.propertyOrNull("db.jdbcUrl")?.getString()?.takeIf { it.isNotBlank() }
    if (dbJdbcUrl == null) {
        logger.info("Using in-memory alerts repository (no DB config)")
        return AlertsRepositoryMemory()
    }

    return runCatchingNonFatal {
        DatabaseFactory.init()
        runBlocking { DatabaseFactory.ping() }
        AlertsRepositoryPostgres()
    }.getOrElse {
        val allowMemoryFallback =
            config.propertyOrNull("alerts.allowMemoryFallbackOnDbError")?.getString()?.toBoolean() ?: false
        if (allowMemoryFallback) {
            logger.warn("Falling back to in-memory alerts repository: {}", it.message)
            AlertsRepositoryMemory()
        } else {
            logger.error("Failed to initialize alerts repository", it)
            throw it
        }
    }
}

private fun Application.ensureBillingServices(
    metrics: DomainMetrics,
    featureFlagsService: FeatureFlagsService,
    adminUserIds: Set<Long>,
    analyticsPort: AnalyticsPort,
    referralsPort: ReferralsPort,
    experimentsPort: ExperimentsPort,
    experimentsService: ExperimentsService,
    newsConfig: NewsConfig,
    deepLinkStore: DeepLinkStore,
    deepLinkTtl: Duration,
): Services {
    if (attributes.contains(Services.Key)) {
        val existing = attributes[Services.Key]
        val enriched = existing.enrich(metrics, featureFlagsService, adminUserIds)
        val updated = enriched.copy(
            analytics = analyticsPort,
            referrals = referralsPort,
            experimentsPort = experimentsPort,
            experimentsService = experimentsService,
            newsConfig = newsConfig,
            deepLinkStore = deepLinkStore,
            deepLinkTtl = deepLinkTtl,
        )
        val currentRoutingServices = if (attributes.contains(BillingRouteServicesKey)) {
            attributes[BillingRouteServicesKey]
        } else {
            null
        }
        attributes.put(Services.Key, updated)
        attributes.put(
            BillingRouteServicesKey,
            BillingRouteServices(
                billingService = updated.billingService,
                starBalancePort = currentRoutingServices?.starBalancePort,
                botStarBalancePort = currentRoutingServices?.botStarBalancePort,
                entitlementsService = currentRoutingServices?.entitlementsService,
                adminUserIds = currentRoutingServices?.adminUserIds ?: adminUserIds,
                meterRegistry = currentRoutingServices?.meterRegistry ?: metrics.meterRegistry,
                starsClient = currentRoutingServices?.starsClient,
            ),
        )
        return updated
    }

    val telegramBot = ensureTelegramBot()

    val billingService = BillingServiceImpl(
        repo = BillingRepositoryImpl(),
        stars = StarsGatewayFactory.fromConfig(environment),
        ledger = BillingLedgerRepository(),
        defaultDurationDays = billingDefaultDuration(),
    )
    var starsClient: StarsClient? = null
    val botToken = environment.config.propertyOrNull("telegram.botToken")?.getString()
        ?: System.getenv("TELEGRAM_BOT_TOKEN")
    val botStarBalancePort: BotStarBalancePort? = botToken?.let {
        val starBalanceConfig = starsClientConfig()
        starsClient = StarsClient(
            botToken = it,
            config = starBalanceConfig,
        )
        val ttl = environment.config.propertyOrNull("billing.stars.balanceTtlSeconds")?.getString()?.toLongOrNull()
        val ttlSeconds = (ttl ?: 20L).coerceAtLeast(5L)
        val maxStaleConfig = environment.config.propertyOrNull(
            "billing.stars.maxStaleSeconds"
        )?.getString()?.toLongOrNull()
        val maxStale = maxOf(ttlSeconds, (maxStaleConfig ?: 300L))
        StarsService(
            client = starsClient!!,
            ttlSeconds = ttlSeconds,
            maxStaleSeconds = maxStale,
            meterRegistry = metrics.meterRegistry,
        )
    }
    val starBalancePort = ZeroStarBalancePort()
    val entitlementsService = EntitlementsService(billingService)
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
        newsConfig = newsConfig,
        deepLinkStore = deepLinkStore,
        deepLinkTtl = deepLinkTtl,
    )
    attributes.put(Services.Key, services)
    attributes.put(
        BillingRouteServicesKey,
        BillingRouteServices(
            billingService = billingService,
            starBalancePort = starBalancePort,
            botStarBalancePort = botStarBalancePort,
            entitlementsService = entitlementsService,
            adminUserIds = adminUserIds,
            botBalanceRateLimiter = botBalanceRateLimiter(environment),
            meterRegistry = metrics.meterRegistry,
            starsClient = starsClient,
        ),
    )
    environment.monitor.subscribe(ApplicationStopped) {
        starsClient?.close()
    }
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
    val baseFromTelegram = config.propertyOrNull("telegram.botUsername")?.getString()?.trim()?.takeIf {
        it.isNotEmpty()
    }?.let { "https://t.me/$it" }
    val botBase = baseFromConfig?.takeIf { it.isNotEmpty() } ?: baseFromTelegram ?: defaults.botDeepLinkBase
    val maxBytes = config.propertyOrNull("news.maxPayloadBytes")?.getString()?.toIntOrNull() ?: defaults.maxPayloadBytes
    val channelId = config.propertyOrNull("news.channelId")?.getString()?.toLongOrNull()
        ?: config.propertyOrNull("telegram.channelId")?.getString()?.toLongOrNull()
        ?: defaults.channelId
    val digestOnly = config.propertyOrNull("news.modeDigestOnly")?.getString()?.toBooleanStrictOrNull()
    val autopublishBreaking = config.propertyOrNull(
        "news.modeAutopublishBreaking"
    )?.getString()?.toBooleanStrictOrNull()
    val explicitMode = NewsMode.parse(config.propertyOrNull("news.mode")?.getString())
    val mode = explicitMode ?: when {
        digestOnly == true -> NewsMode.DIGEST_ONLY
        autopublishBreaking == true -> NewsMode.AUTOPUBLISH
        else -> defaults.mode
    }
    val digestMinIntervalSeconds = config.propertyOrNull("news.digestMinIntervalSeconds")?.getString()?.toLongOrNull()
        ?.coerceAtLeast(0L) ?: defaults.digestMinIntervalSeconds
    val maxPostsPerDay = config.propertyOrNull("news.antiNoise.maxPostsPerDay")?.getString()?.toIntOrNull()
        ?.coerceAtLeast(0) ?: defaults.antiNoise.maxPostsPerDay
    val minIntervalBreakingMinutes = config.propertyOrNull("news.antiNoise.minIntervalBreakingMinutes")
        ?.getString()?.toLongOrNull()?.coerceAtLeast(0L) ?: defaults.antiNoise.minIntervalBreakingMinutes
    val digestSlots = parseDigestSlots(
        config.propertyOrNull("news.antiNoise.digestSlots")?.getString(),
        defaults.antiNoise.digestSlots,
    )
    val breakingThreshold = config.propertyOrNull("news.scoring.breakingThreshold")?.getString()?.toDoubleOrNull()
        ?: defaults.scoring.breakingThreshold
    val digestMinScore = config.propertyOrNull("news.scoring.digestMinScore")?.getString()?.toDoubleOrNull()
        ?: defaults.scoring.digestMinScore
    val minConfidenceAutopublish = config.propertyOrNull("news.scoring.minConfidenceAutopublish")?.getString()
        ?.toDoubleOrNull() ?: defaults.scoring.minConfidenceAutopublish
    val primaryTickers = parseTickers(
        config.propertyOrNull("news.scoring.primaryTickers")?.getString(),
        defaults.eventScoring.primaryTickers,
    )
    val primaryTickerBoost = config.propertyOrNull("news.scoring.primaryTickerBoost")?.getString()?.toDoubleOrNull()
        ?.coerceAtLeast(1.0) ?: defaults.eventScoring.primaryTickerBoost
    val moderationEnabled = config.propertyOrNull("news.moderation.enabled")?.getString()?.toBooleanStrictOrNull()
        ?: defaults.moderationEnabled
    val moderationTier0Weight = config.propertyOrNull("news.moderation.tier0Weight")?.getString()?.toIntOrNull()
        ?: defaults.moderationTier0Weight
    val moderationConfidenceThreshold = config.propertyOrNull("news.moderation.confidenceThreshold")?.getString()
        ?.toDoubleOrNull() ?: defaults.moderationConfidenceThreshold
    val moderationBreakingAgeMinutes = config.propertyOrNull("news.moderation.breakingAgeMinutes")?.getString()
        ?.toLongOrNull() ?: defaults.moderationBreakingAgeMinutes
    return defaults.copy(
        botDeepLinkBase = botBase,
        maxPayloadBytes = maxBytes,
        channelId = channelId,
        mode = mode,
        digestMinIntervalSeconds = digestMinIntervalSeconds,
        antiNoise = defaults.antiNoise.copy(
            maxPostsPerDay = maxPostsPerDay,
            minIntervalBreakingMinutes = minIntervalBreakingMinutes,
            digestSlots = digestSlots,
        ),
        scoring = defaults.scoring.copy(
            breakingThreshold = breakingThreshold,
            digestMinScore = digestMinScore,
            minConfidenceAutopublish = minConfidenceAutopublish,
        ),
        eventScoring = defaults.eventScoring.copy(
            primaryTickers = primaryTickers,
            primaryTickerBoost = primaryTickerBoost,
        ),
        moderationEnabled = moderationEnabled,
        moderationTier0Weight = moderationTier0Weight,
        moderationConfidenceThreshold = moderationConfidenceThreshold,
        moderationBreakingAgeMinutes = moderationBreakingAgeMinutes
    )
}

private fun parseDigestSlots(raw: String?, fallback: List<LocalTime>): List<LocalTime> {
    if (raw.isNullOrBlank()) return fallback
    val formatter = DateTimeFormatter.ofPattern("H:mm")
    return raw.split(",")
        .mapNotNull { value ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            runCatching { LocalTime.parse(trimmed, formatter) }.getOrNull()
        }
        .ifEmpty { fallback }
}

private fun parseTickers(raw: String?, fallback: Set<String>): Set<String> {
    if (raw.isNullOrBlank()) return fallback
    return raw.split(",")
        .mapNotNull { value ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            trimmed.uppercase()
        }
        .toSet()
        .ifEmpty { fallback }
}

private fun Application.loadModerationBotConfig(newsConfig: NewsConfig): ModerationBotConfig? {
    if (!newsConfig.moderationEnabled) {
        return null
    }
    val config = environment.config
    val adminChatId = config.propertyOrNull("news.moderation.adminChatId")?.getString()?.toLongOrNull()
    requireNotNull(adminChatId) { "news.moderation.adminChatId must be set when moderation is enabled" }
    val adminThreadId = config.propertyOrNull("news.moderation.adminThreadId")?.getString()?.toLongOrNull()
    val muteHours = config.propertyOrNull("news.moderation.muteHours")?.getString()?.toLongOrNull() ?: 24L
    return ModerationBotConfig(
        enabled = true,
        adminChatId = adminChatId,
        adminThreadId = adminThreadId,
        muteHours = muteHours,
    )
}

private class NewsPipelineScheduler(
    private val scope: CoroutineScope,
    private val job: Job,
    private val httpClient: HttpClient
) {
    suspend fun stop() {
        job.cancelAndJoin()
        httpClient.close()
    }
}

private fun Application.startNewsPipelineScheduler(
    newsConfig: NewsConfig,
    services: Services,
    metrics: DomainMetrics,
    config: ApplicationConfig,
    deepLinkStore: DeepLinkStore,
    deepLinkTtl: Duration,
    moderationQueue: ModerationQueue,
    publishJobQueue: PublishJobQueue,
): NewsPipelineScheduler {
    val logger = LoggerFactory.getLogger("NewsPipelineScheduler")
    if (newsConfig.mode == NewsMode.HYBRID && !newsConfig.moderationEnabled) {
        logger.warn("News mode HYBRID with moderation disabled; Tier1/2 items will not reach review")
    }
    val intervalRaw = config.propertyOrNull("news.pipelineIntervalSeconds")?.getString()?.toLongOrNull()
    val intervalSeconds = if (intervalRaw == null || intervalRaw <= 0L) {
        logger.warn(
            "Invalid news.pipelineIntervalSeconds={}, using safe default of 60s",
            intervalRaw ?: "null"
        )
        60L
    } else {
        intervalRaw
    }
    val httpClient = RssFetcher.defaultClient(newsConfig)
    val fetcher = RssFetcher(newsConfig, httpClient)
    val sources = listOf(
        CbrSource(fetcher),
        MoexSource(fetcher)
    )
    val idempotencyStore = DbIdempotencyStore()
    val postStatsStore = DbPostStatsStore()
    val telegramPublisher = TelegramPublisher(
        client = PengradTelegramClient(services.telegramBot),
        config = newsConfig,
        postStatsStore = postStatsStore,
        idempotencyStore = idempotencyStore,
        metrics = services.newsMetrics ?: NewsMetricsPort.Noop,
    )
    val pipeline = NewsPipeline(
        config = newsConfig,
        sources = sources,
        clusterer = Clusterer(newsConfig),
        telegramPublisher = telegramPublisher,
        deepLinkStore = deepLinkStore,
        deepLinkTtl = deepLinkTtl,
        moderationQueue = moderationQueue,
        publishJobQueue = publishJobQueue,
        metrics = services.newsMetrics ?: NewsMetricsPort.Noop,
    )
    val digestWorker = DigestPublishWorker(
        queue = publishJobQueue,
        publisher = telegramPublisher,
        instanceId = UUID.randomUUID().toString(),
    )

    val meterRegistry = metrics.meterRegistry
    val timer = meterRegistry.timer("news_pipeline_run_seconds")
    val counterOk = meterRegistry.counter("news_pipeline_run_total", "result", "ok")
    val counterEmpty = meterRegistry.counter("news_pipeline_run_total", "result", "empty")
    val counterError = meterRegistry.counter("news_pipeline_run_total", "result", "error")
    val digestPublished = meterRegistry.counter("news_digest_published_total")
    val digestRunOk = meterRegistry.counter("news_digest_run_total", "result", "ok")
    val digestRunEmpty = meterRegistry.counter("news_digest_run_total", "result", "empty")
    val digestRunError = meterRegistry.counter("news_digest_run_total", "result", "error")
    val lastSuccess = AtomicLong(0L)
    meterRegistry.gauge("news_pipeline_last_success_ts", lastSuccess)

    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.IO + CoroutineName("news-pipeline-scheduler"))
    scope.launch {
        while (isActive) {
            val sample = Timer.start(meterRegistry)
            val result = runCatchingNonFatal { pipeline.runOnce() }
            sample.stop(timer)
            result.onSuccess { published ->
                lastSuccess.set(Instant.now().epochSecond)
                if (published > 0) {
                    counterOk.increment()
                } else {
                    counterEmpty.increment()
                }
            }.onFailure { ex ->
                counterError.increment()
                logger.error("News pipeline run failed", ex)
            }
            delay(intervalSeconds.seconds)
        }
    }
    scope.launch {
        while (isActive) {
            val result = runCatchingNonFatal { digestWorker.runOnce() }
            result.onSuccess { published ->
                if (published > 0) {
                    digestPublished.increment(published.toDouble())
                    digestRunOk.increment()
                } else {
                    digestRunEmpty.increment()
                }
            }.onFailure { ex ->
                digestRunError.increment()
                logger.error("Digest worker failed", ex)
            }
            delay(intervalSeconds.seconds)
        }
    }
    return NewsPipelineScheduler(scope, job, httpClient)
}

private fun Application.billingDefaultDuration(): Long {
    val raw = environment.config.propertyOrNull("billing.defaultDurationDays")?.getString()
    return raw?.toLongOrNull() ?: 30L
}

private fun Application.starsClientConfig(): StarsClientConfig {
    val httpConfig = runCatchingNonFatal { environment.config.config("billing.stars.http") }.getOrNull()
    return StarsClientConfig(
        connectTimeoutMs = httpConfig?.propertyOrNull("connectTimeoutMs")?.getString()?.toLongOrNull() ?: 2000L,
        readTimeoutMs = httpConfig?.propertyOrNull("readTimeoutMs")?.getString()?.toLongOrNull() ?: 3000L,
        retryMax = httpConfig?.propertyOrNull("retryMax")?.getString()?.toIntOrNull() ?: 3,
        retryBaseDelayMs = httpConfig?.propertyOrNull("retryBaseDelayMs")?.getString()?.toLongOrNull() ?: 200L,
    )
}

private fun Application.botBalanceRateLimiter(environment: ApplicationEnvironment): BotBalanceRateLimiter? {
    val perMinute = environment.config.propertyOrNull(
        "billing.stars.adminRateLimitPerMinute"
    )?.getString()?.toIntOrNull()
    val capacity = (perMinute ?: 30).coerceAtLeast(1)
    return BotBalanceRateLimiter(capacity = capacity, refillPerMinute = capacity)
}

private data class WebhookQueueConfig(
    val capacity: Int,
    val workers: Int,
    val mode: OverflowMode,
    val shutdownTimeout: Duration
)

private fun Application.validateTelegramWebhookConfig(config: TelegramWebhookConfig) {
    if (!config.enabled) {
        return
    }
    if (config.secret != null) {
        return
    }
    val logger = LoggerFactory.getLogger("TelegramWebhook")
    logger.error("telegram webhook secret is missing while webhook mode is enabled")
    if (config.failFastOnMissingSecret) {
        throw IllegalStateException("telegram webhook secret is required when webhook mode is enabled")
    }
}

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
    analytics: AnalyticsPort,
    eventsCounter: EventsCounter
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
                eventsCounter.inc("stars_payment_succeeded")
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
    val tier = runCatchingNonFatal { billing.model.Tier.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
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
    val deepLinkStore: DeepLinkStore,
    val deepLinkTtl: Duration,
) {
    companion object {
        val Key: AttributeKey<Services> = AttributeKey("AppServices")
    }
}
