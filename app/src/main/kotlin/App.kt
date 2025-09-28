package app

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
import billing.service.BillingService
import billing.service.BillingServiceImpl
import com.pengrad.telegrambot.BotUtils
import di.ensureTelegramBot
import demo.demoRoutes
import di.installPortfolioModule
import health.healthRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
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
import observability.DomainMetrics
import observability.Observability
import observability.WebhookMetrics
import org.slf4j.LoggerFactory
import repo.BillingRepositoryImpl
import routes.BillingRouteServices
import routes.BillingRouteServicesKey
import routes.authRoutes
import routes.billingRoutes
import routes.portfolioImportRoutes
import routes.portfolioPositionsTradesRoutes
import routes.portfolioRoutes
import routes.portfolioValuationReportRoutes
import routes.quotesRoutes
import security.installSecurity
import security.installUploadGuard
import webhook.OverflowMode
import webhook.WebhookQueue

fun Application.module() {
    val prometheusRegistry = Observability.install(this)
    val metrics = DomainMetrics(prometheusRegistry)
    val webhookMetrics = WebhookMetrics.create(prometheusRegistry)

    installSecurity()
    installUploadGuard()
    installPortfolioModule()
    val services = ensureBillingServices(metrics)

    val queueConfig = webhookQueueConfig()
    val webhookQueue = WebhookQueue(
        capacity = queueConfig.capacity,
        mode = queueConfig.mode,
        workers = queueConfig.workers,
        scope = this,
        metrics = webhookMetrics
    ) { update ->
        processStarsPayment(update, services.billingService, services.metrics)
    }
    webhookQueue.start()

    environment.monitor.subscribe(ApplicationStopped) {
        runBlocking {
            webhookQueue.shutdown(queueConfig.shutdownTimeout)
        }
    }

    routing {
        healthRoutes()
        authRoutes()
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
            if (route == BotRoute.Unknown) {
                return@post
            }

            launch {
                when (route) {
                    Plans -> StarsBotCommands.handlePlans(botUpdate, bot, billingSvc)
                    Buy -> StarsBotCommands.handleBuy(botUpdate, bot, billingSvc)
                    Status -> StarsBotCommands.handleStatus(botUpdate, bot, billingSvc)
                    Callback -> StarsBotCommands.handleCallback(botUpdate, bot, billingSvc)
                    BotRoute.Unknown -> Unit
                }
            }
        }

        authenticate("auth-jwt") {
            portfolioRoutes()
            portfolioPositionsTradesRoutes()
            portfolioImportRoutes()
            portfolioValuationReportRoutes()
            billingRoutes()
        }
    }
}

private fun Application.ensureBillingServices(metrics: DomainMetrics): Services {
    if (attributes.contains(Services.Key)) {
        val existing = attributes[Services.Key]
        if (existing.metrics == null) {
            val enriched = existing.copy(metrics = metrics)
            attributes.put(Services.Key, enriched)
            return enriched
        }
        return existing
    }

    val telegramBot = ensureTelegramBot()

    val billingService = BillingServiceImpl(
        repo = BillingRepositoryImpl(),
        stars = StarsGatewayFactory.fromConfig(environment),
        defaultDurationDays = billingDefaultDuration(),
    )
    val services = Services(billingService = billingService, telegramBot = telegramBot, metrics = metrics)
    attributes.put(Services.Key, services)
    attributes.put(BillingRouteServicesKey, BillingRouteServices(billingService))
    return services
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
    metrics: DomainMetrics?
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

    val result = billing.applySuccessfulPayment(
        userId = userId,
        tier = tier,
        amountXtr = successfulPayment.total_amount,
        providerPaymentId = successfulPayment.provider_payment_charge_id,
        payload = successfulPayment.invoice_payload
    )

    if (result.isSuccess) {
        metrics?.webhookStarsSuccess?.increment()
        webhookLogger.info("webhook-queue reason=applied_ok")
    } else {
        webhookLogger.error("webhook-queue reason=apply_failed", result.exceptionOrNull())
    }
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
    val metrics: DomainMetrics? = null,
) {
    companion object {
        val Key: AttributeKey<Services> = AttributeKey("AppServices")
    }
}
