package http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

class SimpleCache<K : Any, V : Any>(
    private val ttlMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Entry<V>(
        val value: V,
        val expiresAtMillis: Long,
    ) {
        fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
    }

    private val store = ConcurrentHashMap<K, Entry<V>>()
    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<V>>()

    suspend fun getOrPut(
        key: K,
        loader: suspend () -> V,
    ): V {
        val now = clock.millis()
        store[key]?.let { entry ->
            if (!entry.isExpired(now)) {
                return entry.value
            }
            store.remove(key, entry)
        }

        inFlight[key]?.let { deferred ->
            return deferred.await()
        }

        val newDeferred = CompletableDeferred<V>()
        val existing = inFlight.putIfAbsent(key, newDeferred)
        if (existing != null) {
            return existing.await()
        }

        try {
            val value = loader()
            val expiresAt = expiryInstant(now)
            if (expiresAt != null) {
                store[key] = Entry(value, expiresAt)
            }
            newDeferred.complete(value)
            return value
        } catch (cancellation: CancellationException) {
            newDeferred.completeExceptionally(cancellation)
            throw cancellation
        } catch (err: Error) {
            newDeferred.completeExceptionally(err)
            throw err
        } catch (t: Throwable) {
            newDeferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, newDeferred)
        }
    }

    fun invalidate(key: K) {
        store.remove(key)
    }

    fun clear() {
        store.clear()
    }

    private fun expiryInstant(nowMillis: Long): Long? {
        if (ttlMillis <= 0L) {
            return null
        }
        val ttl = ttlMillis
        val expiresAt = nowMillis + ttl
        return if (expiresAt < 0L) Long.MAX_VALUE else expiresAt
    }
}
