package cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class TtlCache<K : Any, V : Any>(
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Entry<V>(
        val value: V,
        val expiresAt: Instant,
    ) {
        fun isExpired(now: Instant): Boolean = now.isAfter(expiresAt)
    }

    private val store = ConcurrentHashMap<K, Entry<V>>()
    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<V>>()

    suspend fun getOrPut(
        key: K,
        ttl: Duration,
        loader: suspend () -> V,
    ): V {
        val now = clock.instant()
        store[key]?.let { entry ->
            if (!entry.isExpired(now)) {
                return entry.value
            }
            store.remove(key, entry)
        }

        inFlight[key]?.let { existing ->
            return existing.await()
        }

        val deferred = CompletableDeferred<V>()
        val previous = inFlight.putIfAbsent(key, deferred)
        if (previous != null) {
            return previous.await()
        }

        try {
            val value = loader()
            val expiresAt = expiryInstant(ttl)
            if (expiresAt != null) {
                store[key] = Entry(value, expiresAt)
            }
            deferred.complete(value)
            return value
        } catch (cancellation: CancellationException) {
            deferred.completeExceptionally(cancellation)
            throw cancellation
        } catch (err: Error) {
            deferred.completeExceptionally(err)
            throw err
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    fun invalidate(key: K) {
        store.remove(key)
    }

    fun clear() {
        store.clear()
    }

    private fun expiryInstant(ttl: Duration): Instant? {
        if (ttl <= ZERO) {
            return null
        }
        val millis = ttl.inWholeMilliseconds
        val expiresIn = if (millis <= 0L) 0L else millis
        return clock.instant().plusMillis(expiresIn)
    }
}
