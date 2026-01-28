package news.moderation

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import news.config.NewsConfig
import news.config.SourceWeight
import news.model.Article
import news.model.Cluster

class ModerationScorerTest {
    @Test
    fun `scores tier0 breaking candidates`() {
        val now = Instant.parse("2024-04-12T10:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val config = NewsConfig(
            userAgent = "test",
            httpTimeoutMs = 1000,
            sourceWeights = listOf(SourceWeight("example.com", 100)),
            channelId = 1L,
            botDeepLinkBase = "https://t.me/example",
            moderationTier0Weight = 90,
            moderationConfidenceThreshold = 0.7,
            moderationBreakingAgeMinutes = 120,
        )
        val cluster = clusterAt("example.com", now.minusSeconds(600))
        val scorer = ModerationScorer(config, clock)
        val score = scorer.score(cluster)
        assertTrue(score.tier0)
        assertTrue(score.confident)
        assertEquals(ModerationSuggestedMode.BREAKING, scorer.suggestedMode(cluster, score))
    }

    @Test
    fun `routes low confidence to digest`() {
        val now = Instant.parse("2024-04-12T10:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val config = NewsConfig(
            userAgent = "test",
            httpTimeoutMs = 1000,
            sourceWeights = listOf(SourceWeight("example.com", 40)),
            channelId = 1L,
            botDeepLinkBase = "https://t.me/example",
            moderationTier0Weight = 90,
            moderationConfidenceThreshold = 0.7,
            moderationBreakingAgeMinutes = 120,
        )
        val cluster = clusterAt("example.com", now.minusSeconds(600))
        val scorer = ModerationScorer(config, clock)
        val score = scorer.score(cluster)
        assertFalse(score.tier0)
        assertFalse(score.confident)
        assertEquals(ModerationSuggestedMode.DIGEST, scorer.suggestedMode(cluster, score))
    }

    private fun clusterAt(domain: String, publishedAt: Instant): Cluster {
        val article = Article(
            id = "1",
            url = "https://$domain/news",
            domain = domain,
            title = "Title",
            summary = "Summary",
            publishedAt = publishedAt,
            language = "ru",
            tickers = emptySet(),
            entities = emptySet(),
        )
        return Cluster(
            clusterKey = "cluster-$domain",
            canonical = article,
            articles = listOf(article),
            topics = setOf("topic"),
            createdAt = publishedAt,
        )
    }
}
