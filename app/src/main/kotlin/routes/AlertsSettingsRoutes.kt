package routes

import alerts.settings.AlertsConfig
import alerts.settings.AlertsOverridePatch
import alerts.settings.AlertsSettingsService
import alerts.settings.AlertsSettingsServiceImpl
import alerts.settings.Budget
import alerts.settings.DynamicScale
import alerts.settings.Hysteresis
import alerts.settings.MatrixV11
import alerts.settings.Percent
import alerts.settings.QuietHours
import alerts.settings.Thresholds
import alerts.settings.validate
import app.Services
import billing.model.Tier
import billing.service.BillingService
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import common.runCatchingNonFatal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import repo.AlertsSettingsRepositoryImpl
import security.requireTierAtLeast
import security.userIdOrNull

private const val ALERTS_CONFIG_PATH = "alerts"

fun Route.alertsSettingsRoutes() {
    route("/api/alerts/settings") {
        get {
            val deps = call.alertsSettingsDependencies()
            val subject = call.userIdOrNull?.toLongOrNull()
            if (subject == null) {
                call.respondUnauthorized()
                return@get
            }
            val result =
                runCatchingNonFatal { deps.settingsService.effectiveFor(subject) }
                    .onFailure { throwable ->
                        call.application.environment.log
                            .error("alerts.settings.fetch_failed", throwable)
                    }.getOrElse { throwable ->
                        call.handleDomainError(throwable)
                        return@get
                    }
            call.respond(result)
        }

        put {
            call.handleOverrideUpdate(isPatch = false)
        }

        patch {
            call.handleOverrideUpdate(isPatch = true)
        }
    }
}

private suspend fun ApplicationCall.handleOverrideUpdate(isPatch: Boolean) {
    val deps = alertsSettingsDependencies()
    val subject = userIdOrNull?.toLongOrNull()
    if (subject == null) {
        respondUnauthorized()
        return
    }

    val patch =
        try {
            receive<AlertsOverridePatch>()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (exception: Throwable) {
            application.environment.log.warn("alerts.settings.invalid_payload", exception)
            respondBadRequest(listOf("Invalid JSON payload"))
            return
        }

    val validationErrors = patch.validate()
    if (validationErrors.isNotEmpty()) {
        respondBadRequest(validationErrors)
        return
    }

    if (!requireTierAtLeast(Tier.PRO, deps.billingService)) {
        return
    }

    val updateSucceeded =
        runCatchingNonFatal { deps.settingsService.upsert(subject, patch) }
            .onFailure { throwable ->
                application.environment.log.error("alerts.settings.update_failed", throwable)
            }.isSuccess

    if (!updateSucceeded) {
        return
    }

    if (!isPatch) {
        application.environment.log.info("alerts.settings.override_replaced", mapOf("userId" to subject))
    }
    respond(HttpStatusCode.NoContent)
}

internal data class AlertsSettingsRouteDeps(
    val billingService: BillingService,
    val settingsService: AlertsSettingsService,
)

internal val AlertsSettingsDepsKey = AttributeKey<AlertsSettingsRouteDeps>("AlertsSettingsDeps")

private fun ApplicationCall.alertsSettingsDependencies(): AlertsSettingsRouteDeps {
    val attributes = application.attributes
    if (attributes.contains(AlertsSettingsDepsKey)) {
        return attributes[AlertsSettingsDepsKey]
    }
    val deps = application.buildDefaultAlertsSettingsDeps()
    attributes.put(AlertsSettingsDepsKey, deps)
    return deps
}

private fun Application.buildDefaultAlertsSettingsDeps(): AlertsSettingsRouteDeps {
    val services = attributes[Services.Key]
    val defaults = loadAlertsDefaults()
    val repository = AlertsSettingsRepositoryImpl()
    val service = AlertsSettingsServiceImpl(defaults, repository, this as CoroutineScope)
    return AlertsSettingsRouteDeps(services.billingService, service)
}

private fun Application.loadAlertsDefaults(): AlertsConfig {
    val rootConfig = ConfigFactory.load()
    val alertsCfg = rootConfig.getConfig(ALERTS_CONFIG_PATH)
    val quietCfg = alertsCfg.getConfig("quiet")
    val budgetCfg = alertsCfg.getConfig("budget")
    val hysteresisCfg = alertsCfg.getConfig("hysteresis")
    val dynamicCfg = alertsCfg.getConfig("dynamic")
    val matrixCfg = alertsCfg.getConfig("matrix")

    val perClassCfg = matrixCfg.getConfig("perClass")
    val perClass =
        perClassCfg.root().entries.associate { entry ->
            val key = entry.key
            val cfg = perClassCfg.getConfig(key)
            key to cfg.toThresholds()
        }

    return AlertsConfig(
        quiet =
        QuietHours(
            start = quietCfg.getString("start"),
            end = quietCfg.getString("end"),
        ),
        budget = Budget(maxPushesPerDay = budgetCfg.getInt("maxPushesPerDay")),
        hysteresis =
        Hysteresis(
            enterPct = Percent(hysteresisCfg.getDouble("enterPct")),
            exitPct = Percent(hysteresisCfg.getDouble("exitPct")),
        ),
        cooldownMinutes = alertsCfg.getInt("cooldownMinutes"),
        dynamic =
        DynamicScale(
            enabled = dynamicCfg.getBoolean("enabled"),
            min = dynamicCfg.getDouble("min"),
            max = dynamicCfg.getDouble("max"),
        ),
        matrix =
        MatrixV11(
            portfolioDayPct = Percent(matrixCfg.getDouble("portfolioDayPct")),
            portfolioDrawdown = Percent(matrixCfg.getDouble("portfolioDrawdown")),
            perClass = perClass,
        ),
    )
}

private fun Config.toThresholds(): Thresholds {
    val pctFast = Percent(getDouble("pctFast"))
    val pctDay = Percent(getDouble("pctDay"))
    val volMult = if (hasPath("volMultFast")) getDouble("volMultFast") else null
    return Thresholds(pctFast = pctFast, pctDay = pctDay, volMultFast = volMult)
}
