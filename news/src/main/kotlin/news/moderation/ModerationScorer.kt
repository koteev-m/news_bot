package news.moderation

import java.time.Clock
import java.time.Duration
import news.canonical.CanonicalPicker
import news.config.NewsConfig
import news.model.Cluster

class ModerationScorer(
    private val config: NewsConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val canonicalPicker = CanonicalPicker(config)

    data class ModerationScore(
        val score: Double,
        val confidence: Double,
        val tier0: Boolean,
        val confident: Boolean,
    )

    fun score(cluster: Cluster): ModerationScore {
        val weight = canonicalPicker.weightFor(cluster.canonical.domain).coerceAtLeast(0)
        val tier0 = weight >= config.moderationTier0Weight
        val freshness = freshnessFactor(cluster)
        val base = weight.toDouble() * freshness
        val confidence = (weight / 100.0) * freshness
        return ModerationScore(
            score = base,
            confidence = confidence,
            tier0 = tier0,
            confident = confidence >= config.moderationConfidenceThreshold,
        )
    }

    fun suggestedMode(cluster: Cluster, score: ModerationScore): ModerationSuggestedMode {
        if (!score.tier0 || !score.confident) {
            return ModerationSuggestedMode.DIGEST
        }
        val ageMinutes = Duration.between(cluster.canonical.publishedAt, clock.instant()).toMinutes()
        return if (ageMinutes <= config.moderationBreakingAgeMinutes) {
            ModerationSuggestedMode.BREAKING
        } else {
            ModerationSuggestedMode.DIGEST
        }
    }

    private fun freshnessFactor(cluster: Cluster): Double {
        val ageMinutes = Duration.between(cluster.canonical.publishedAt, clock.instant()).toMinutes()
        return when {
            ageMinutes <= 60 -> 1.0
            ageMinutes <= 180 -> 0.85
            ageMinutes <= 720 -> 0.6
            ageMinutes <= 1440 -> 0.4
            else -> 0.2
        }
    }
}
