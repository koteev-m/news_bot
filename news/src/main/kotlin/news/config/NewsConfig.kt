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

data class RssBackoffConfig(
    val minFailures: Int,
    val baseCooldownSeconds: Long,
    val maxCooldownSeconds: Long,
)

data class EventScoringConfig(
    val eventSeverity: Map<news.model.EventType, Double>,
    val primaryTickers: Set<String>,
    val primaryTickerBoost: Double,
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
    val eventScoring: EventScoringConfig = NewsDefaults.defaultEventScoring,
    val rssBackoff: RssBackoffConfig = NewsDefaults.defaultRssBackoff,
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

    val defaultEventScoring = EventScoringConfig(
        eventSeverity = mapOf(
            news.model.EventType.CBR_RATE to 1.4,
            news.model.EventType.CBR_STATEMENT to 1.2,
            news.model.EventType.MOEX_TRADING_STATUS to 1.3,
            news.model.EventType.LISTING_DELISTING to 1.15,
            news.model.EventType.CORPORATE_ACTION to 1.1,
            news.model.EventType.MARKET_NEWS to 1.0,
            news.model.EventType.UNKNOWN to 0.8,
        ),
        primaryTickers = setOf("GAZP", "SBER", "LKOH", "YNDX", "ROSN"),
        primaryTickerBoost = 1.15,
    )

    val defaultRssBackoff = RssBackoffConfig(
        minFailures = 2,
        baseCooldownSeconds = 60,
        maxCooldownSeconds = 3600,
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
        eventScoring = defaultEventScoring,
        rssBackoff = defaultRssBackoff,
        moderationEnabled = false,
        moderationTier0Weight = 90,
        moderationConfidenceThreshold = 0.7,
        moderationBreakingAgeMinutes = 90
    )

    fun weightFor(domain: String, weights: List<SourceWeight> = defaultWeights): Int {
        return weights.firstOrNull { domain.endsWith(it.domain, ignoreCase = true) }?.weight ?: 0
    }
}
