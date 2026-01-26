package deeplink

import io.ktor.server.config.ApplicationConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

data class DeepLinkStoreSettings(
    val enabled: Boolean,
    val ttlDays: Long,
    val redis: RedisSettings?
)

data class RedisSettings(
    val host: String,
    val port: Int,
    val db: Int,
    val password: String?
)

fun loadDeepLinkStoreSettings(config: ApplicationConfig, log: Logger): DeepLinkStoreSettings {
    val enabled = config.propertyOrNull("deeplink.store.enabled")?.getString()?.toBooleanStrictOrNull() ?: true
    val ttlRaw = config.propertyOrNull("deeplink.ttlDays")?.getString()?.toLongOrNull() ?: 14L
    val ttlDays = ttlRaw.coerceIn(7L, 30L).also {
        if (it != ttlRaw) {
            log.warn("deeplink.ttlDays={} out of range, using {}", ttlRaw, it)
        }
    }
    val host = config.propertyOrNull("deeplink.redis.host")?.getString()?.trim()?.takeIf { it.isNotEmpty() }
    val port = config.propertyOrNull("deeplink.redis.port")?.getString()?.toIntOrNull() ?: 6379
    val db = config.propertyOrNull("deeplink.redis.db")?.getString()?.toIntOrNull() ?: 0
    val password = config.propertyOrNull("deeplink.redis.password")?.getString()?.takeIf { it.isNotBlank() }
    val redis = if (host == null) null else RedisSettings(host = host, port = port, db = db, password = password)
    return DeepLinkStoreSettings(enabled = enabled, ttlDays = ttlDays, redis = redis)
}

fun createDeepLinkStore(
    settings: DeepLinkStoreSettings,
    appProfile: String,
    log: Logger = LoggerFactory.getLogger("deeplink"),
): DeepLinkStore {
    if (!settings.enabled) {
        log.warn("deeplink.store.enabled=false; using in-memory store")
        return InMemoryDeepLinkStore()
    }
    val redis = settings.redis
    if (redis == null) {
        val normalizedProfile = appProfile.lowercase()
        val allowFallback = normalizedProfile == "dev" || normalizedProfile == "staging"
        if (!allowFallback) {
            throw IllegalStateException(
                "deeplink.redis.host must be set when deeplink.store.enabled=true for APP_PROFILE=$appProfile"
            )
        }
        log.warn("deeplink.redis host not configured; using in-memory store for APP_PROFILE={}", appProfile)
        return InMemoryDeepLinkStore()
    }
    return RedisDeepLinkStore(
        client = RedisClient.create(buildRedisUri(redis)),
        json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        },
    )
}

fun deepLinkTtl(settings: DeepLinkStoreSettings): Duration = settings.ttlDays.days

private fun buildRedisUri(redis: RedisSettings): RedisURI {
    val builder = RedisURI.Builder.redis(redis.host, redis.port).withDatabase(redis.db)
    redis.password?.let { builder.withPassword(it.toCharArray()) }
    return builder.build()
}

class RedisDeepLinkStore(
    private val client: RedisClient,
    private val json: Json,
    private val maxAttempts: Int = 5,
    private val keyPrefix: String = "deeplink:"
) : DeepLinkStore, AutoCloseable {
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override fun put(payload: DeepLinkPayload, ttl: Duration): String {
        require(ttl.isPositive()) { "ttl must be positive" }
        val ttlSeconds = ttl.inWholeSeconds.coerceAtLeast(1)
        val value = json.encodeToString(DeepLinkPayload.serializer(), payload)
        val sync = connection.sync()
        repeat(maxAttempts) {
            val code = DeepLinkCodeGenerator.generate()
            val key = keyPrefix + code
            val result = sync.set(key, value, SetArgs.Builder.ex(ttlSeconds).nx())
            if (result != null) {
                return code
            }
        }
        error("Unable to allocate deep link code after $maxAttempts attempts")
    }

    override fun get(shortCode: String): DeepLinkPayload? {
        val value = connection.sync().get(keyPrefix + shortCode) ?: return null
        return runCatching { json.decodeFromString(DeepLinkPayload.serializer(), value) }.getOrNull()
    }

    override fun delete(shortCode: String) {
        connection.sync().del(keyPrefix + shortCode)
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }
}
