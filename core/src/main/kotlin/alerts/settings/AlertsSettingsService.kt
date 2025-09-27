package alerts.settings

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface AlertsSettingsRepository {
    suspend fun upsert(userId: Long, json: String)
    suspend fun find(userId: Long): String?
}

interface AlertsSettingsService {
    val updatesFlow: StateFlow<Long>
    suspend fun defaults(): AlertsConfig
    suspend fun effectiveFor(userId: Long): AlertsConfig
    suspend fun upsert(userId: Long, patch: AlertsOverridePatch)
}

class AlertsSettingsServiceImpl(
    private val defaults: AlertsConfig,
    private val repository: AlertsSettingsRepository,
    scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
) : AlertsSettingsService {
    @Suppress("unused")
    private val applicationScope: CoroutineScope = scope

    private val cache = ConcurrentHashMap<Long, AlertsOverridePatch>()
    private val _updatesFlow = MutableStateFlow(0L)

    override val updatesFlow: StateFlow<Long> = _updatesFlow.asStateFlow()

    override suspend fun defaults(): AlertsConfig = defaults

    override suspend fun effectiveFor(userId: Long): AlertsConfig {
        val patch = loadPatch(userId)
        return patch?.let { defaults.merge(it) } ?: defaults
    }

    override suspend fun upsert(userId: Long, patch: AlertsOverridePatch) {
        val current = loadPatch(userId)
        val merged = mergePatches(current, patch).normalized()
        val serialized = json.encodeToString(merged)
        repository.upsert(userId, serialized)
        if (merged.isEmpty()) {
            cache.remove(userId)
        } else {
            cache[userId] = merged
        }
        _updatesFlow.update { value -> value + 1 }
    }

    private suspend fun loadPatch(userId: Long): AlertsOverridePatch? {
        cache[userId]?.let { return it }
        val stored = repository.find(userId) ?: return null
        val decoded = try {
            json.decodeFromString<AlertsOverridePatch>(stored)
        } catch (exception: SerializationException) {
            throw IllegalStateException("Failed to decode alerts override for user $userId", exception)
        }
        val normalized = decoded.normalized()
        if (!normalized.isEmpty()) {
            cache[userId] = normalized
        }
        return normalized
    }

    private fun mergePatches(
        existing: AlertsOverridePatch?,
        incoming: AlertsOverridePatch
    ): AlertsOverridePatch {
        if (existing == null) {
            return incoming
        }
        return AlertsOverridePatch(
            cooldownMinutes = incoming.cooldownMinutes ?: existing.cooldownMinutes,
            budgetPerDay = incoming.budgetPerDay ?: existing.budgetPerDay,
            quietHours = mergeQuiet(existing.quietHours, incoming.quietHours),
            hysteresis = mergeHysteresis(existing.hysteresis, incoming.hysteresis),
            dynamic = mergeDynamic(existing.dynamic, incoming.dynamic),
            thresholds = mergeThresholds(existing.thresholds, incoming.thresholds)
        )
    }

    private fun mergeQuiet(
        base: QuietHoursPatch?,
        incoming: QuietHoursPatch?
    ): QuietHoursPatch? {
        if (base == null && incoming == null) {
            return null
        }
        val start = incoming?.start ?: base?.start
        val end = incoming?.end ?: base?.end
        return if (start == null && end == null) null else QuietHoursPatch(start, end)
    }

    private fun mergeHysteresis(
        base: HysteresisPatch?,
        incoming: HysteresisPatch?
    ): HysteresisPatch? {
        if (base == null && incoming == null) {
            return null
        }
        val enter = incoming?.enterPct ?: base?.enterPct
        val exit = incoming?.exitPct ?: base?.exitPct
        return if (enter == null && exit == null) null else HysteresisPatch(enterPct = enter, exitPct = exit)
    }

    private fun mergeDynamic(
        base: DynamicPatch?,
        incoming: DynamicPatch?
    ): DynamicPatch? {
        if (base == null && incoming == null) {
            return null
        }
        val enabled = incoming?.enabled ?: base?.enabled
        val min = incoming?.min ?: base?.min
        val max = incoming?.max ?: base?.max
        return if (enabled == null && min == null && max == null) {
            null
        } else {
            DynamicPatch(enabled = enabled, min = min, max = max)
        }
    }

    private fun mergeThresholds(
        base: Map<String, ThresholdsPatch>?,
        incoming: Map<String, ThresholdsPatch>?
    ): Map<String, ThresholdsPatch>? {
        if ((base == null || base.isEmpty()) && (incoming == null || incoming.isEmpty())) {
            return null
        }
        val result = mutableMapOf<String, ThresholdsPatch>()
        base?.forEach { (key, patch) ->
            result[key] = patch
        }
        incoming?.forEach { (key, patch) ->
            val existing = result[key]
            val merged = ThresholdsPatch(
                pctFast = patch.pctFast ?: existing?.pctFast,
                pctDay = patch.pctDay ?: existing?.pctDay,
                volMultFast = patch.volMultFast ?: existing?.volMultFast
            )
            if (merged.pctFast == null && merged.pctDay == null && merged.volMultFast == null) {
                result.remove(key)
            } else {
                result[key] = merged
            }
        }
        return if (result.isEmpty()) null else result.toMap()
    }

    private fun AlertsOverridePatch.normalized(): AlertsOverridePatch {
        val quiet = quietHours?.takeIf { it.start != null || it.end != null }
        val hyst = hysteresis?.takeIf { it.enterPct != null || it.exitPct != null }
        val dyn = dynamic?.takeIf { it.enabled != null || it.min != null || it.max != null }
        val thresholdsNormalized = thresholds
            ?.mapNotNull { (key, patch) ->
                val normalized = ThresholdsPatch(
                    pctFast = patch.pctFast,
                    pctDay = patch.pctDay,
                    volMultFast = patch.volMultFast
                )
                if (normalized.pctFast == null && normalized.pctDay == null && normalized.volMultFast == null) {
                    null
                } else {
                    key to normalized
                }
            }
            ?.toMap()

        return AlertsOverridePatch(
            cooldownMinutes = cooldownMinutes,
            budgetPerDay = budgetPerDay,
            quietHours = quiet,
            hysteresis = hyst,
            dynamic = dyn,
            thresholds = thresholdsNormalized
        )
    }

    private fun AlertsOverridePatch.isEmpty(): Boolean {
        return cooldownMinutes == null &&
            budgetPerDay == null &&
            quietHours == null &&
            hysteresis == null &&
            dynamic == null &&
            thresholds.isNullOrEmpty()
    }
}
