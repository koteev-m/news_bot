package news.scoring

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import news.classification.EventCandidate
import news.config.NewsDefaults
import news.model.Article
import news.model.Cluster
import news.model.EventType

class EventScorerTest {
    @Test
    fun `score boosts primary ticker relevance`() {
        val now = Instant.parse("2024-06-01T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val config = NewsDefaults.defaultConfig.copy(
            eventScoring = NewsDefaults.defaultEventScoring.copy(
                primaryTickers = setOf("SBER"),
                primaryTickerBoost = 1.2,
                eventSeverity = NewsDefaults.defaultEventScoring.eventSeverity + (EventType.MARKET_NEWS to 1.0),
            )
        )
        val scorer = EventScorer(config, clock)
        val article = Article(
            id = "id-1",
            url = "https://cbr.ru/news",
            domain = "cbr.ru",
            title = "Новость",
            summary = "Детали",
            publishedAt = now,
            language = "ru",
            tickers = setOf("SBER"),
            entities = emptySet()
        )
        val cluster = Cluster(
            clusterKey = "cluster-1",
            canonical = article,
            articles = listOf(article),
            topics = emptySet(),
            createdAt = now
        )
        val candidate = EventCandidate(
            eventType = EventType.MARKET_NEWS,
            mainEntity = "SBER",
            confidence = 1.0
        )

        val score = scorer.score(cluster, candidate)

        assertEquals(120.0, score.score, 0.001)
        assertEquals(1.2, score.entityRelevance, 0.001)
    }
}
