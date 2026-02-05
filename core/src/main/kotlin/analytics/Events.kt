package analytics

import java.time.Instant

interface AnalyticsPort {
    suspend fun track(
        type: String,
        userId: Long?,
        source: String?,
        sessionId: String? = null,
        props: Map<String, Any?> = emptyMap(),
        ts: Instant = Instant.now(),
    )

    companion object {
        val Noop: AnalyticsPort =
            object : AnalyticsPort {
                override suspend fun track(
                    type: String,
                    userId: Long?,
                    source: String?,
                    sessionId: String?,
                    props: Map<String, Any?>,
                    ts: Instant,
                ) {
                    // no-op
                }
            }
    }
}
