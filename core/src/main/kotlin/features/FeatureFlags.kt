package features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FeatureFlags(
    val importByUrl: Boolean,
    val webhookQueue: Boolean,
    val newsPublish: Boolean,
    val alertsEngine: Boolean,
    val billingStars: Boolean,
    val miniApp: Boolean,
)

@Serializable
data class FeatureFlagsPatch(
    val importByUrl: Boolean? = null,
    val webhookQueue: Boolean? = null,
    val newsPublish: Boolean? = null,
    val alertsEngine: Boolean? = null,
    val billingStars: Boolean? = null,
    val miniApp: Boolean? = null,
)

fun FeatureFlags.merge(patch: FeatureFlagsPatch): FeatureFlags = FeatureFlags(
    importByUrl = patch.importByUrl ?: importByUrl,
    webhookQueue = patch.webhookQueue ?: webhookQueue,
    newsPublish = patch.newsPublish ?: newsPublish,
    alertsEngine = patch.alertsEngine ?: alertsEngine,
    billingStars = patch.billingStars ?: billingStars,
    miniApp = patch.miniApp ?: miniApp,
)

fun FeatureFlagsPatch.validate(): List<String> = emptyList()

interface FeatureOverridesRepository {
    suspend fun upsertGlobal(json: String)
    suspend fun findGlobal(): String?
}

interface FeatureFlagsService {
    val updatesFlow: StateFlow<Long>
    suspend fun defaults(): FeatureFlags
    suspend fun effective(): FeatureFlags
    suspend fun upsertGlobal(patch: FeatureFlagsPatch)
}

class FeatureFlagsValidationException(val errors: List<String>) : IllegalArgumentException(
    "Invalid feature flags patch"
)

@OptIn(ExperimentalSerializationApi::class)
class FeatureFlagsServiceImpl(
    private val defaults: FeatureFlags,
    private val repository: FeatureOverridesRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
) : FeatureFlagsService {
    private val mutex = Mutex()
    private val _updatesFlow = MutableStateFlow(0L)
    @Volatile
    private var loaded = false
    @Volatile
    private var globalPatch: FeatureFlagsPatch = FeatureFlagsPatch()

    override val updatesFlow: StateFlow<Long> = _updatesFlow.asStateFlow()

    override suspend fun defaults(): FeatureFlags = defaults

    override suspend fun effective(): FeatureFlags {
        val patch = currentPatch()
        return defaults.merge(patch)
    }

    override suspend fun upsertGlobal(patch: FeatureFlagsPatch) {
        val errors = patch.validate()
        if (errors.isNotEmpty()) {
            throw FeatureFlagsValidationException(errors)
        }
        mutex.withLock {
            val existing = ensureLoadedLocked()
            val merged = mergePatches(existing, patch)
            val normalized = merged.normalized()
            if (normalized == existing) {
                return@withLock
            }
            val serialized = json.encodeToString(normalized)
            repository.upsertGlobal(serialized)
            globalPatch = normalized
            _updatesFlow.update { value -> value + 1 }
        }
    }

    private suspend fun currentPatch(): FeatureFlagsPatch = mutex.withLock { ensureLoadedLocked() }

    private suspend fun ensureLoadedLocked(): FeatureFlagsPatch {
        if (!loaded) {
            val stored = repository.findGlobal()
            globalPatch = stored?.let { decodePatch(it) } ?: FeatureFlagsPatch()
            loaded = true
        }
        return globalPatch
    }

    private fun decodePatch(raw: String): FeatureFlagsPatch {
        return try {
            json.decodeFromString<FeatureFlagsPatch>(raw).normalized()
        } catch (exception: SerializationException) {
            throw IllegalStateException("Failed to decode feature overrides", exception)
        }
    }

    private fun mergePatches(
        base: FeatureFlagsPatch,
        incoming: FeatureFlagsPatch
    ): FeatureFlagsPatch {
        return FeatureFlagsPatch(
            importByUrl = incoming.importByUrl ?: base.importByUrl,
            webhookQueue = incoming.webhookQueue ?: base.webhookQueue,
            newsPublish = incoming.newsPublish ?: base.newsPublish,
            alertsEngine = incoming.alertsEngine ?: base.alertsEngine,
            billingStars = incoming.billingStars ?: base.billingStars,
            miniApp = incoming.miniApp ?: base.miniApp,
        )
    }

    private fun FeatureFlagsPatch.normalized(): FeatureFlagsPatch {
        return FeatureFlagsPatch(
            importByUrl = importByUrl,
            webhookQueue = webhookQueue,
            newsPublish = newsPublish,
            alertsEngine = alertsEngine,
            billingStars = billingStars,
            miniApp = miniApp,
        )
    }
}
