package news.config

import java.time.LocalTime

data class SourceWeight(
    val domain: String,
    val weight: Int
)

enum class NewsMode {
    DIGEST_ONLY,
    HYBRID,
    AUTOPUBLISH;

    companion object {
        fun parse(raw: String?): NewsMode? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

data class AntiNoiseConfig(
    val maxPostsPerDay: Int,
    val minIntervalBreakingMinutes: Long,
    val digestSlots: List<LocalTime>,
)

data class NewsScoringConfig(
    val breakingThreshold: Double,
    val digestMinScore: Double,
    val minConfidenceAutopublish: Double,
)

data class NewsConfig(
    val userAgent: String,
    val httpTimeoutMs: Long,
    val sourceWeights: List<SourceWeight>,
    val channelId: Long,
    val botDeepLinkBase: String,
    val maxPayloadBytes: Int = 64,
    val mode: NewsMode = NewsMode.DIGEST_ONLY,
    val digestMinIntervalSeconds: Long = 21_600,
    val antiNoise: AntiNoiseConfig = NewsDefaults.defaultAntiNoise,
    val scoring: NewsScoringConfig = NewsDefaults.defaultScoring,
    val moderationEnabled: Boolean = false,
    val moderationTier0Weight: Int = 90,
    val moderationConfidenceThreshold: Double = 0.7,
    val moderationBreakingAgeMinutes: Long = 90
)

object NewsDefaults {
    private val defaultWeights = listOf(
        SourceWeight("cbr.ru", 100),
        SourceWeight("moex.com", 95),
        SourceWeight("interfax.ru", 70),
        SourceWeight("ria.ru", 65),
        SourceWeight("vedomosti.ru", 60),
        SourceWeight("rbk.ru", 55),
        SourceWeight("kommersant.ru", 50)
    )

    val defaultAntiNoise = AntiNoiseConfig(
        maxPostsPerDay = 12,
        minIntervalBreakingMinutes = 30,
        digestSlots = listOf(LocalTime.of(9, 0)),
    )

    val defaultScoring = NewsScoringConfig(
        breakingThreshold = 80.0,
        digestMinScore = 45.0,
        minConfidenceAutopublish = 0.65,
    )

    val defaultConfig: NewsConfig = NewsConfig(
        userAgent = "news-bot/1.0",
        httpTimeoutMs = 30_000,
        sourceWeights = defaultWeights,
        channelId = 0L,
        botDeepLinkBase = "https://t.me/example_bot",
        maxPayloadBytes = 64,
        digestMinIntervalSeconds = 21_600,
        mode = NewsMode.DIGEST_ONLY,
        antiNoise = defaultAntiNoise,
        scoring = defaultScoring,
        moderationEnabled = false,
        moderationTier0Weight = 90,
        moderationConfidenceThreshold = 0.7,
        moderationBreakingAgeMinutes = 90
    )

    fun weightFor(domain: String, weights: List<SourceWeight> = defaultWeights): Int {
        return weights.firstOrNull { domain.endsWith(it.domain, ignoreCase = true) }?.weight ?: 0
    }
}
