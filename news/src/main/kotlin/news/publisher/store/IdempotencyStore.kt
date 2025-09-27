package news.publisher.store

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface IdempotencyStore {
    fun seen(key: String): Boolean
    fun mark(key: String)
}

class InMemoryIdempotencyStore(
    private val ttlMinutes: Long = 1_440,
    private val clock: Clock = Clock.systemUTC()
) : IdempotencyStore {
    private val entries = ConcurrentHashMap<String, Instant>()

    override fun seen(key: String): Boolean {
        purgeExpired()
        val expiresAt = entries[key] ?: return false
        if (expiresAt.isBefore(clock.instant())) {
            entries.remove(key)
            return false
        }
        return true
    }

    override fun mark(key: String) {
        purgeExpired()
        val expiry = clock.instant().plusSeconds(ttlMinutes * 60)
        entries[key] = expiry
    }

    private fun purgeExpired() {
        val now = clock.instant()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isBefore(now)) {
                iterator.remove()
            }
        }
    }
}
