package news.routing

import news.config.NewsConfig
import news.config.NewsMode
import news.scoring.EventScore

enum class EventRoute {
    PUBLISH_NOW,
    DIGEST,
    REVIEW,
    DROP,
}

enum class DropReason {
    LOW_SCORE,
    REVIEW_DISABLED,
}

data class RouteDecision(
    val route: EventRoute,
    val dropReason: DropReason? = null,
)

class EventRouter(private val config: NewsConfig) {
    fun route(score: EventScore): RouteDecision {
        val allowAutopublish = config.mode != NewsMode.DIGEST_ONLY
        val minConfidence = config.scoring.minConfidenceAutopublish
        return when {
            score.tier0 &&
                allowAutopublish &&
                score.score >= config.scoring.breakingThreshold &&
                score.confidence >= minConfidence -> {
                RouteDecision(EventRoute.PUBLISH_NOW)
            }
            score.score >= config.scoring.digestMinScore -> {
                RouteDecision(EventRoute.DIGEST)
            }
            shouldReview() -> {
                RouteDecision(EventRoute.REVIEW)
            }
            else -> {
                RouteDecision(EventRoute.DROP, DropReason.LOW_SCORE)
            }
        }
    }

    private fun shouldReview(): Boolean {
        return config.moderationEnabled || config.mode == NewsMode.HYBRID
    }
}
