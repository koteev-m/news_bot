package news.scoring

import java.time.Clock
import java.time.Duration
import news.canonical.CanonicalPicker
import news.classification.EventCandidate
import news.config.NewsConfig
import news.model.Cluster

data class EventScore(
    val score: Double,
    val confidence: Double,
    val sourceWeight: Int,
    val freshnessDecay: Double,
    val entityRelevance: Double,
    val eventSeverity: Double,
    val tier0: Boolean,
)

class EventScorer(
    private val config: NewsConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val canonicalPicker = CanonicalPicker(config)

    fun score(
        cluster: Cluster,
        candidate: EventCandidate,
    ): EventScore {
        val sourceWeight = canonicalPicker.weightFor(cluster.canonical.domain).coerceAtLeast(0)
        val freshnessDecay = freshnessDecay(cluster)
        val entityRelevance = entityRelevance(candidate.mainEntity)
        val eventSeverity = config.eventScoring.eventSeverity[candidate.eventType] ?: 1.0
        val confidence = candidate.confidence.coerceIn(0.0, 1.0)
        val score = sourceWeight * freshnessDecay * entityRelevance * eventSeverity * confidence
        return EventScore(
            score = score,
            confidence = confidence,
            sourceWeight = sourceWeight,
            freshnessDecay = freshnessDecay,
            entityRelevance = entityRelevance,
            eventSeverity = eventSeverity,
            tier0 = sourceWeight >= config.moderationTier0Weight,
        )
    }

    private fun entityRelevance(mainEntity: String?): Double {
        if (mainEntity.isNullOrBlank()) return 1.0
        val normalized = mainEntity.uppercase()
        return if (config.eventScoring.primaryTickers.any { it.equals(normalized, ignoreCase = true) }) {
            config.eventScoring.primaryTickerBoost
        } else {
            1.0
        }
    }

    private fun freshnessDecay(cluster: Cluster): Double {
        val ageMinutes = Duration.between(cluster.canonical.publishedAt, clock.instant()).toMinutes()
        return when {
            ageMinutes <= FRESHNESS_AGE_MINUTES_30 -> FRESHNESS_DECAY_RECENT
            ageMinutes <= FRESHNESS_AGE_MINUTES_120 -> FRESHNESS_DECAY_WARM
            ageMinutes <= FRESHNESS_AGE_MINUTES_360 -> FRESHNESS_DECAY_STALE
            ageMinutes <= FRESHNESS_AGE_MINUTES_720 -> FRESHNESS_DECAY_OLD
            ageMinutes <= FRESHNESS_AGE_MINUTES_1440 -> FRESHNESS_DECAY_VERY_OLD
            else -> FRESHNESS_DECAY_COLD
        }
    }

    private companion object {
        const val FRESHNESS_AGE_MINUTES_30 = 30L
        const val FRESHNESS_AGE_MINUTES_120 = 120L
        const val FRESHNESS_AGE_MINUTES_360 = 360L
        const val FRESHNESS_AGE_MINUTES_720 = 720L
        const val FRESHNESS_AGE_MINUTES_1440 = 1440L
        const val FRESHNESS_DECAY_RECENT = 1.0
        const val FRESHNESS_DECAY_WARM = 0.9
        const val FRESHNESS_DECAY_STALE = 0.75
        const val FRESHNESS_DECAY_OLD = 0.6
        const val FRESHNESS_DECAY_VERY_OLD = 0.4
        const val FRESHNESS_DECAY_COLD = 0.25
    }
}
