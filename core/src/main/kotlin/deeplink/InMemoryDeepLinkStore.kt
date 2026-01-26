package deeplink

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class InMemoryDeepLinkStore(
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = 5
) : DeepLinkStore {
    private val entries = ConcurrentHashMap<String, Entry>()

    override fun put(payload: DeepLinkPayload, ttl: Duration): String {
        require(ttl.isPositive()) { "ttl must be positive" }
        val expiresAt = clock.instant().plusSeconds(ttl.inWholeSeconds.coerceAtLeast(1))
        cleanupExpired(clock.instant())
        repeat(maxAttempts) {
            val code = DeepLinkCodeGenerator.generate()
            val existing = entries.putIfAbsent(code, Entry(payload, expiresAt))
            if (existing == null) {
                return code
            }
        }
        error("Unable to allocate deep link code after $maxAttempts attempts")
    }

    override fun get(shortCode: String): DeepLinkPayload? {
        val entry = entries[shortCode] ?: return null
        if (entry.expiresAt.isBefore(clock.instant())) {
            entries.remove(shortCode)
            return null
        }
        return entry.payload
    }

    override fun delete(shortCode: String) {
        entries.remove(shortCode)
    }

    private fun cleanupExpired(now: Instant) {
        if (entries.isEmpty()) {
            return
        }
        entries.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

    private data class Entry(
        val payload: DeepLinkPayload,
        val expiresAt: Instant
    )
}
