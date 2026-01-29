package news.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import news.config.NewsDefaults
import news.config.NewsMode
import news.scoring.EventScore

class EventRouterTest {
    @Test
    fun `routes to publish now when breaking conditions met`() {
        val config = NewsDefaults.defaultConfig.copy(mode = NewsMode.AUTOPUBLISH)
        val router = EventRouter(config)
        val score = EventScore(
            score = 95.0,
            confidence = 0.8,
            sourceWeight = 100,
            freshnessDecay = 1.0,
            entityRelevance = 1.0,
            eventSeverity = 1.0,
            tier0 = true,
        )

        val decision = router.route(score)

        assertEquals(EventRoute.PUBLISH_NOW, decision.route)
    }

    @Test
    fun `routes to digest when score is sufficient but confidence below breaking minimum`() {
        val config = NewsDefaults.defaultConfig.copy(mode = NewsMode.AUTOPUBLISH)
        val router = EventRouter(config)
        val score = EventScore(
            score = 60.0,
            confidence = 0.5,
            sourceWeight = 95,
            freshnessDecay = 1.0,
            entityRelevance = 1.0,
            eventSeverity = 1.0,
            tier0 = true,
        )

        val decision = router.route(score)

        assertEquals(EventRoute.DIGEST, decision.route)
    }

    @Test
    fun `routes to review when below digest but moderation enabled`() {
        val config = NewsDefaults.defaultConfig.copy(
            mode = NewsMode.HYBRID,
            moderationEnabled = true,
        )
        val router = EventRouter(config)
        val score = EventScore(
            score = 10.0,
            confidence = 0.2,
            sourceWeight = 10,
            freshnessDecay = 0.5,
            entityRelevance = 1.0,
            eventSeverity = 1.0,
            tier0 = false,
        )

        val decision = router.route(score)

        assertEquals(EventRoute.REVIEW, decision.route)
    }

    @Test
    fun `routes to drop when below digest and review disabled`() {
        val config = NewsDefaults.defaultConfig.copy(mode = NewsMode.DIGEST_ONLY, moderationEnabled = false)
        val router = EventRouter(config)
        val score = EventScore(
            score = 5.0,
            confidence = 0.1,
            sourceWeight = 0,
            freshnessDecay = 0.2,
            entityRelevance = 1.0,
            eventSeverity = 1.0,
            tier0 = false,
        )

        val decision = router.route(score)

        assertEquals(EventRoute.DROP, decision.route)
        assertEquals(DropReason.LOW_SCORE, decision.dropReason)
    }
}
