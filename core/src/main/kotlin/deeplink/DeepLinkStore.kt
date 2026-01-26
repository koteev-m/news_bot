package deeplink

import kotlin.time.Duration

interface DeepLinkStore {
    fun put(payload: DeepLinkPayload, ttl: Duration): String

    fun get(shortCode: String): DeepLinkPayload?

    fun delete(shortCode: String)
}
