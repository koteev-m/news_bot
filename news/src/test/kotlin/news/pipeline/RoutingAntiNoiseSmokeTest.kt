package news.pipeline

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import news.classification.EventClassifier
import news.config.AntiNoiseConfig
import news.config.NewsConfig
import news.config.NewsDefaults
import news.config.NewsMode
import news.config.NewsScoringConfig
import news.model.Article
import news.model.Cluster
import news.routing.EventRoute
import news.routing.EventRouter
import news.scoring.EventScorer

class RoutingAntiNoiseSmokeTest {
    private val now = Instant.parse("2024-06-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val classifier = EventClassifier()

    @Test
    fun `routes clusters with thresholds and anti-noise limits`() = runTest {
        val config = NewsDefaults.defaultConfig.copy(
            mode = NewsMode.AUTOPUBLISH,
            moderationEnabled = true,
            scoring = NewsScoringConfig(
                breakingThreshold = 80.0,
                digestMinScore = 30.0,
                minConfidenceAutopublish = 0.6
            ),
            antiNoise = AntiNoiseConfig(
                maxPostsPerDay = 1,
                minIntervalBreakingMinutes = 60,
                digestSlots = listOf(LocalTime.of(9, 0))
            )
        )

        val breakingCluster = cluster(
            domain = "cbr.ru",
            title = "Ключевая ставка повышена",
            summary = "Совет директоров принял решение"
        )
        val digestCluster = cluster(
            domain = "interfax.ru",
            title = "Рынок акций прибавил 1%",
            summary = "Индекс Мосбиржи растет"
        )
        val reviewCluster = cluster(
            domain = "kommersant.ru",
            title = "Обзор рынка без драйверов",
            summary = "Участники рынка ждут новостей"
        )

        val allowHistory = FakeBreakingHistory(lastBreaking = null, publishedToday = 0)
        val blockedHistory = FakeBreakingHistory(lastBreaking = now.minusSeconds(10 * 60), publishedToday = 1)

        assertEquals(
            EventRoute.PUBLISH_NOW,
            resolveRoute(config, breakingCluster, allowHistory),
        )
        assertEquals(
            EventRoute.DIGEST,
            resolveRoute(config, breakingCluster, blockedHistory),
        )
        assertEquals(
            EventRoute.DIGEST,
            resolveRoute(config, digestCluster, allowHistory),
        )
        assertEquals(
            EventRoute.REVIEW,
            resolveRoute(config, reviewCluster, allowHistory),
        )

        val dropConfig = config.copy(mode = NewsMode.DIGEST_ONLY, moderationEnabled = false)
        assertEquals(
            EventRoute.DROP,
            resolveRoute(dropConfig, reviewCluster, allowHistory),
        )
    }

    private suspend fun resolveRoute(
        config: NewsConfig,
        cluster: Cluster,
        history: BreakingHistory,
    ): EventRoute {
        val scorer = EventScorer(config, clock)
        val router = EventRouter(config)
        val candidate = classifier.classify(cluster)
        val score = scorer.score(cluster, candidate)
        val decision = router.route(score)
        if (decision.route != EventRoute.PUBLISH_NOW) {
            return decision.route
        }
        val policy = AntiNoisePolicy(config.antiNoise, history, clock, ZoneOffset.UTC)
        val breakingDecision = policy.allowBreaking()
        return if (breakingDecision.allowed) EventRoute.PUBLISH_NOW else EventRoute.DIGEST
    }

    private fun cluster(
        domain: String,
        title: String,
        summary: String,
    ): Cluster {
        val article = Article(
            id = "id-$domain-$title",
            url = "https://$domain/news",
            domain = domain,
            title = title,
            summary = summary,
            publishedAt = now.minusSeconds(60),
            language = "ru",
            tickers = emptySet(),
            entities = emptySet()
        )
        return Cluster(
            clusterKey = "cluster-$domain-$title",
            canonical = article,
            articles = listOf(article),
            topics = emptySet(),
            createdAt = article.publishedAt
        )
    }

    private class FakeBreakingHistory(
        private val lastBreaking: Instant?,
        private val publishedToday: Int,
    ) : BreakingHistory {
        override suspend fun lastBreakingPublishedAt(): Instant? = lastBreaking

        override suspend fun countBreakingPublishedSince(since: Instant): Int = publishedToday
    }
}
