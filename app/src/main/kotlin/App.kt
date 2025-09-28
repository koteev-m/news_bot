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
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import observability.DomainMetrics
import observability.Observability
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

fun Application.module() {
    val prometheusRegistry = Observability.install(this)
    val metrics = DomainMetrics(prometheusRegistry)

    installSecurity()
    installUploadGuard()
    installPortfolioModule()
    ensureBillingServices(metrics)

    routing {
        // Публичные
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

            val services = attributes[Services.Key]
            val rawUpdate = runCatching { call.receiveText() }.getOrElse {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val tgUpdate = runCatching { StarsWebhookHandler.json.decodeFromString<TgUpdate>(rawUpdate) }.getOrElse {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            StarsWebhookHandler.handleParsed(call, tgUpdate, services.billingService)

            val update = runCatching { BotUtils.parseUpdate(rawUpdate) }.getOrNull()
            if (update == null) {
                return@post
            }

            val bot = services.telegramBot
            val billingSvc = services.billingService
            val route = StarsBotRouter.route(update)
            if (route == BotRoute.Unknown) {
                return@post
            }

            launch {
                when (route) {
                    Plans -> StarsBotCommands.handlePlans(update, bot, billingSvc)
                    Buy -> StarsBotCommands.handleBuy(update, bot, billingSvc)
                    Status -> StarsBotCommands.handleStatus(update, bot, billingSvc)
                    Callback -> StarsBotCommands.handleCallback(update, bot, billingSvc)
                    BotRoute.Unknown -> Unit
                }
            }
        }

        // Защищённые (под JWT)
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

data class Services(
    val billingService: BillingService,
    val telegramBot: com.pengrad.telegrambot.TelegramBot,
    val metrics: DomainMetrics? = null,
) {
    companion object {
        val Key: AttributeKey<Services> = AttributeKey("AppServices")
    }
}
