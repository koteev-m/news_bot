package alerts.settings

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class Percent(val value: Double) {
    init {
        require(value.isFinite()) { "Percent value must be finite" }
    }
}

@Serializable
data class QuietHours(
    val start: String,
    val end: String
)

@Serializable
data class Budget(
    val maxPushesPerDay: Int
)

@Serializable
data class Hysteresis(
    val enterPct: Percent,
    val exitPct: Percent
)

@Serializable
data class Thresholds(
    val pctFast: Percent,
    val pctDay: Percent,
    val volMultFast: Double? = null
)

@Serializable
data class MatrixV11(
    val portfolioDayPct: Percent,
    val portfolioDrawdown: Percent,
    val perClass: Map<String, Thresholds>
)

@Serializable
data class DynamicScale(
    val enabled: Boolean,
    val min: Double,
    val max: Double
)

@Serializable
data class AlertsConfig(
    val quiet: QuietHours,
    val budget: Budget,
    val hysteresis: Hysteresis,
    val cooldownMinutes: Int,
    val dynamic: DynamicScale,
    val matrix: MatrixV11
)

@Serializable
data class QuietHoursPatch(
    val start: String? = null,
    val end: String? = null
)

@Serializable
data class HysteresisPatch(
    val enterPct: Double? = null,
    val exitPct: Double? = null
)

@Serializable
data class DynamicPatch(
    val enabled: Boolean? = null,
    val min: Double? = null,
    val max: Double? = null
)

@Serializable
data class ThresholdsPatch(
    val pctFast: Double? = null,
    val pctDay: Double? = null,
    val volMultFast: Double? = null
)

@Serializable
data class AlertsOverridePatch(
    val cooldownMinutes: Int? = null,
    val budgetPerDay: Int? = null,
    val quietHours: QuietHoursPatch? = null,
    val hysteresis: HysteresisPatch? = null,
    val dynamic: DynamicPatch? = null,
    val thresholds: Map<String, ThresholdsPatch>? = null
)

fun AlertsConfig.merge(patch: AlertsOverridePatch): AlertsConfig {
    val mergedQuiet = patch.quietHours?.let { overrides ->
        QuietHours(
            start = overrides.start ?: quiet.start,
            end = overrides.end ?: quiet.end
        )
    } ?: quiet

    val mergedBudget = patch.budgetPerDay?.let { value ->
        Budget(maxPushesPerDay = value)
    } ?: budget

    val mergedHysteresis = patch.hysteresis?.let { overrides ->
        Hysteresis(
            enterPct = overrides.enterPct?.let(::Percent) ?: hysteresis.enterPct,
            exitPct = overrides.exitPct?.let(::Percent) ?: hysteresis.exitPct
        )
    } ?: hysteresis

    val mergedDynamic = patch.dynamic?.let { overrides ->
        DynamicScale(
            enabled = overrides.enabled ?: dynamic.enabled,
            min = overrides.min ?: dynamic.min,
            max = overrides.max ?: dynamic.max
        )
    } ?: dynamic

    val mergedThresholds = mergeThresholds(matrix.perClass, patch.thresholds)

    val mergedCooldown = patch.cooldownMinutes ?: cooldownMinutes

    return AlertsConfig(
        quiet = mergedQuiet,
        budget = mergedBudget,
        hysteresis = mergedHysteresis,
        cooldownMinutes = mergedCooldown,
        dynamic = mergedDynamic,
        matrix = MatrixV11(
            portfolioDayPct = matrix.portfolioDayPct,
            portfolioDrawdown = matrix.portfolioDrawdown,
            perClass = mergedThresholds
        )
    )
}

fun AlertsOverridePatch.validate(): List<String> {
    val errors = mutableListOf<String>()
    cooldownMinutes?.let { value ->
        if (value < 5 || value > 720) {
            errors += "cooldownMinutes must be between 5 and 720"
        }
    }
    budgetPerDay?.let { value ->
        if (value < 1 || value > 100) {
            errors += "budgetPerDay must be between 1 and 100"
        }
    }

    quietHours?.let { patch ->
        patch.start?.let { start ->
            if (!start.isValidTime()) {
                errors += "quietHours.start must be in HH:mm format"
            }
        }
        patch.end?.let { end ->
            if (!end.isValidTime()) {
                errors += "quietHours.end must be in HH:mm format"
            }
        }
    }

    hysteresis?.let { patch ->
        val enter = patch.enterPct
        val exit = patch.exitPct
        if (enter != null && enter <= 0.0) {
            errors += "hysteresis.enterPct must be positive"
        }
        if (exit != null && exit <= 0.0) {
            errors += "hysteresis.exitPct must be positive"
        }
        if (enter != null && exit != null && enter <= exit) {
            errors += "hysteresis.enterPct must be greater than hysteresis.exitPct"
        }
    }

    dynamic?.let { patch ->
        val min = patch.min
        val max = patch.max
        if (min != null && (min < 0.5 || min > 2.0)) {
            errors += "dynamic.min must be between 0.5 and 2.0"
        }
        if (max != null && (max < 0.5 || max > 2.0)) {
            errors += "dynamic.max must be between 0.5 and 2.0"
        }
        if (min != null && max != null && min > max) {
            errors += "dynamic.min must be less than or equal to dynamic.max"
        }
        if (min != null && min > 1.0) {
            errors += "dynamic.min must not exceed 1.0"
        }
        if (max != null && max < 1.0) {
            errors += "dynamic.max must be at least 1.0"
        }
    }

    thresholds?.forEach { (key, patch) ->
        patch.pctFast?.let { value ->
            if (value <= 0.0) {
                errors += "thresholds[$key].pctFast must be positive"
            }
        }
        patch.pctDay?.let { value ->
            if (value <= 0.0) {
                errors += "thresholds[$key].pctDay must be positive"
            }
        }
        patch.volMultFast?.let { value ->
            if (value <= 0.0) {
                errors += "thresholds[$key].volMultFast must be positive"
            }
        }
    }

    return errors
}

private fun mergeThresholds(
    defaults: Map<String, Thresholds>,
    overrides: Map<String, ThresholdsPatch>?
): Map<String, Thresholds> {
    if (overrides.isNullOrEmpty()) {
        return defaults
    }
    val result = defaults.toMutableMap()
    overrides.forEach { (key, patch) ->
        val base = result[key]
        val pctFast = patch.pctFast?.let(::Percent) ?: base?.pctFast
        val pctDay = patch.pctDay?.let(::Percent) ?: base?.pctDay
        val volMult = patch.volMultFast ?: base?.volMultFast
        if (pctFast != null && pctDay != null) {
            result[key] = Thresholds(
                pctFast = pctFast,
                pctDay = pctDay,
                volMultFast = volMult
            )
        }
    }
    return result
}

private fun String.isValidTime(): Boolean {
    return try {
        LocalTime.parse(this, TIME_FORMATTER)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
