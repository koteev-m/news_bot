package news.publisher

import java.time.Clock
import java.time.Duration
import java.time.Instant

interface IdempotencyStore {
    fun seen(clusterKey: String): Boolean
    fun mark(clusterKey: String)
}

class InMemoryIdempotencyStore(
    private val ttl: Duration = Duration.ofHours(6),
    private val clock: Clock = Clock.systemUTC()
) : IdempotencyStore {
    private val entries = mutableMapOf<String, Instant>()

    override fun seen(clusterKey: String): Boolean {
        purgeExpired()
        val expiresAt = entries[clusterKey] ?: return false
        if (expiresAt.isBefore(clock.instant())) {
            entries.remove(clusterKey)
            return false
        }
        return true
    }

    override fun mark(clusterKey: String) {
        purgeExpired()
        entries[clusterKey] = clock.instant().plus(ttl)
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
