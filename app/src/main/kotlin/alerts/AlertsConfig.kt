package alerts

import common.runCatchingNonFatal
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

data class AlertsConfig(
    val engine: EngineConfig,
    val internalToken: String?,
)

private val logger = LoggerFactory.getLogger("alerts.config")

fun loadAlertsConfig(config: ApplicationConfig): AlertsConfig {
    val defaults = EngineConfig()
    val quietStart = config.getIntOrDefault("alerts.quietHours.startHour", defaults.quietHours.startHour)
    val quietEnd = config.getIntOrDefault("alerts.quietHours.endHour", defaults.quietHours.endHour)
    val dailyBudget = config.getIntOrDefault("alerts.dailyBudgetPushMax", defaults.dailyBudgetPushMax)
    val hysteresisExitFactor = config.getDoubleOrDefault("alerts.hysteresisExitFactor", defaults.hysteresisExitFactor)
    val volumeGateK = config.getDoubleOrDefault("alerts.volumeGateK", defaults.volumeGateK)
    val confirmMin =
        config.getLongOrDefault(
            "alerts.confirmT.minMinutes",
            defaults.confirmT.min.inWholeMinutes,
        )
    val confirmMax =
        config.getLongOrDefault(
            "alerts.confirmT.maxMinutes",
            defaults.confirmT.max.inWholeMinutes,
        )
    val cooldownMin =
        config.getLongOrDefault(
            "alerts.cooldownT.minMinutes",
            defaults.cooldownT.min.inWholeMinutes,
        )
    val cooldownMax =
        config.getLongOrDefault(
            "alerts.cooldownT.maxMinutes",
            defaults.cooldownT.max.inWholeMinutes,
        )
    val zoneId =
        config
            .propertyOrNull("alerts.zoneId")
            ?.getString()
            ?.takeIf { it.isNotBlank() }
            ?.let(ZoneId::of)
            ?: defaults.zoneId
    val thresholds = loadThresholds(config, defaults.thresholds)
    val internalToken = config.propertyOrNull("alerts.internalToken")?.getString()?.takeIf { it.isNotBlank() }

    val engine =
        EngineConfig(
            confirmT = DurationRange(confirmMin.minutes, confirmMax.minutes),
            cooldownT = DurationRange(cooldownMin.minutes, cooldownMax.minutes),
            quietHours = QuietHours(quietStart, quietEnd),
            dailyBudgetPushMax = dailyBudget,
            hysteresisExitFactor = hysteresisExitFactor,
            volumeGateK = volumeGateK,
            thresholds = thresholds,
            zoneId = zoneId,
        )
    return AlertsConfig(engine = engine, internalToken = internalToken)
}

private fun loadThresholds(
    config: ApplicationConfig,
    defaults: ThresholdMatrix,
): ThresholdMatrix {
    val base = defaults.entries.toMutableMap()
    val thresholdsConfig =
        runCatchingNonFatal { config.config("alerts.thresholds") }.getOrNull()
            ?: return defaults
    val classIds = thresholdsConfig.keys() ?: emptySet()
    var overridesApplied = false

    classIds.forEach { classId ->
        val entry = runCatchingNonFatal { thresholdsConfig.config(classId) }.getOrNull() ?: return@forEach
        val fast = entry.propertyOrNull("fast")?.getString()?.toDoubleOrNull()
        val daily = entry.propertyOrNull("daily")?.getString()?.toDoubleOrNull()
        val defaultThresholds = defaults.entries[classId]
        when {
            fast == null && daily == null -> return@forEach
            defaultThresholds == null -> {
                if (fast != null && daily != null) {
                    base[classId] = Thresholds(fast = fast, daily = daily)
                    overridesApplied = true
                } else {
                    logger.warn(
                        "Ignoring partial thresholds override for unknown classId {} (need both fast and daily)",
                        classId,
                    )
                }
            }
            else -> {
                val resolvedFast = fast ?: defaultThresholds.fast
                val resolvedDaily = daily ?: defaultThresholds.daily
                base[classId] = Thresholds(fast = resolvedFast, daily = resolvedDaily)
                overridesApplied = true
            }
        }
    }
    return if (!overridesApplied) defaults else ThresholdMatrix(base)
}

private fun ApplicationConfig.getIntOrDefault(
    path: String,
    default: Int,
): Int = propertyOrNull(path)?.getString()?.toIntOrNull() ?: default

private fun ApplicationConfig.getLongOrDefault(
    path: String,
    default: Long,
): Long = propertyOrNull(path)?.getString()?.toLongOrNull() ?: default

private fun ApplicationConfig.getDoubleOrDefault(
    path: String,
    default: Double,
): Double = propertyOrNull(path)?.getString()?.toDoubleOrNull() ?: default
