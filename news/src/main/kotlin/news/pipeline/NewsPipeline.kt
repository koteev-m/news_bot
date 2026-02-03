package news.pipeline

import deeplink.DeepLinkPayload
import deeplink.DeepLinkStore
import deeplink.DeepLinkType
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import news.config.NewsConfig
import news.classification.EventClassifier
import news.dedup.Clusterer
import news.metrics.NewsMetricsPort
import news.model.Article
import news.model.Cluster
import news.moderation.ModerationCandidate
import news.moderation.ModerationHashes
import news.moderation.ModerationIds
import news.moderation.ModerationQueue
import news.moderation.ModerationSuggestedMode
import news.publisher.PublishResult
import news.publisher.TelegramPublisher
import news.routing.EventRoute
import news.routing.EventRouter
import news.routing.RouteDecision
import news.scoring.EventScore
import news.scoring.EventScorer
import news.sources.NewsSource
import org.slf4j.LoggerFactory
import kotlin.text.Charsets
import common.runCatchingNonFatal
import kotlin.time.Duration

class NewsPipeline(
    private val config: NewsConfig,
    private val sources: List<NewsSource>,
    private val clusterer: Clusterer,
    private val telegramPublisher: TelegramPublisher,
    private val deepLinkStore: DeepLinkStore,
    private val deepLinkTtl: Duration,
    private val moderationQueue: ModerationQueue = ModerationQueue.Noop,
    private val clock: Clock = Clock.systemUTC(),
    private val eventClassifier: EventClassifier = EventClassifier(),
    private val eventScorer: EventScorer = EventScorer(config, clock),
    private val eventRouter: EventRouter = EventRouter(config),
    private val publishJobQueue: PublishJobQueue = PublishJobQueue.Noop,
    private val metrics: NewsMetricsPort = NewsMetricsPort.Noop,
) {
    private val logger = LoggerFactory.getLogger(NewsPipeline::class.java)
    private val digestScheduler = DigestSlotScheduler(
        slots = config.antiNoise.digestSlots,
        fallbackIntervalSeconds = config.digestMinIntervalSeconds,
        clock = clock,
    )
    private val antiNoisePolicy = AntiNoisePolicy(
        config = config.antiNoise,
        history = publishJobQueue,
        clock = clock,
    )

    suspend fun runOnce(): Int = coroutineScope {
        val fetchedArticles = fetchSources()
        if (fetchedArticles.isEmpty()) {
            logger.info("No articles fetched")
            metrics.setDedupRatio(0.0)
            return@coroutineScope 0
        }
        val clusters = clusterer.cluster(fetchedArticles)
        metrics.setDedupRatio(dedupRatio(fetchedArticles.size, clusters.size))
        if (clusters.isEmpty()) {
            logger.info("No clusters generated")
            return@coroutineScope 0
        }
        val outcomes = handleClusters(clusters)
        val breakingPublished = outcomes.count { it == PublishOutcomeType.BREAKING_PUBLISHED }
        if (breakingPublished > 0) {
            logger.info("Published {} breaking posts", breakingPublished)
        }
        breakingPublished
    }

    private suspend fun fetchSources(): List<Article> = coroutineScope {
        sources.map { source ->
            async {
                runCatchingNonFatal { source.fetch() }
                    .onFailure { logger.warn("Source {} failed", source.name, it) }
                    .getOrDefault(emptyList())
                    .also { articles -> metrics.incCandidatesReceived(source.name, articles.size) }
            }
        }.awaitAll().flatten()
    }

    private fun dedupRatio(candidates: Int, clusters: Int): Double {
        if (candidates <= 0) return 0.0
        val uniqueRatio = clusters.toDouble() / candidates.toDouble()
        return (1.0 - uniqueRatio).coerceIn(0.0, 1.0)
    }

    private suspend fun handleClusters(clusters: List<Cluster>): List<PublishOutcomeType> {
        val outcomes = mutableListOf<PublishOutcomeType>()
        for (cluster in clusters.sortedByDescending { it.canonical.publishedAt }) {
            val eventCandidate = eventClassifier.classify(cluster)
            metrics.incClustersCreated(eventCandidate.eventType)
            val score = eventScorer.score(cluster, eventCandidate)
            val decision = eventRouter.route(score)
            metrics.incRouted(decision.route)
            if (decision.route == EventRoute.DROP && decision.dropReason != null) {
                metrics.incDropped(decision.dropReason)
            }
            val entityHashes = extractEntityHashes(cluster)
            if (isMuted(cluster.canonical.domain, entityHashes)) {
                logger.info("Cluster {} skipped due to mute", cluster.clusterKey)
                outcomes += PublishOutcomeType.SKIPPED_MUTED
                continue
            }
            logger.info(
                "Cluster {} routed as {} (eventType={}, score={}, confidence={})",
                cluster.clusterKey,
                decision.route,
                eventCandidate.eventType,
                "%.2f".format(score.score),
                "%.2f".format(score.confidence),
            )
            if (decision.route == EventRoute.DROP) {
                logger.info(
                    "Cluster {} dropped (reason={})",
                    cluster.clusterKey,
                    decision.dropReason ?: "unspecified",
                )
                outcomes += PublishOutcomeType.DROPPED
                continue
            }
            val deepLink = deepLink(cluster)
            val candidate = buildCandidate(cluster, score, decision, entityHashes, deepLink)
            outcomes += handleRoute(cluster, candidate, decision, score)
        }
        return outcomes
    }

    private suspend fun handleRoute(
        cluster: Cluster,
        candidate: ModerationCandidate,
        decision: RouteDecision,
        score: EventScore,
    ): PublishOutcomeType {
        return when (decision.route) {
            EventRoute.PUBLISH_NOW -> publishBreaking(cluster, candidate, score)
            EventRoute.DIGEST -> enqueueDigest(cluster, candidate, score)
            EventRoute.REVIEW -> queueForReview(candidate)
            EventRoute.DROP -> {
                logger.info(
                    "Cluster {} dropped (reason={})",
                    cluster.clusterKey,
                    decision.dropReason ?: "unspecified",
                )
                PublishOutcomeType.DROPPED
            }
        }
    }

    private suspend fun enqueueDigest(
        cluster: Cluster,
        candidate: ModerationCandidate,
        score: EventScore,
    ): PublishOutcomeType {
        val scheduledAt = digestScheduler.nextSlot()
        publishJobQueue.enqueue(
            buildPublishJob(
                candidate = candidate,
                target = PublishTarget.DIGEST,
                scheduledAt = scheduledAt,
            )
        )
        logger.debug("Cluster {} queued for digest at {}", cluster.clusterKey, scheduledAt)
        return PublishOutcomeType.DIGEST_QUEUED
    }

    private suspend fun queueForReview(candidate: ModerationCandidate): PublishOutcomeType {
        if (config.moderationEnabled) {
            moderationQueue.enqueue(candidate)
        }
        publishJobQueue.enqueue(
            buildPublishJob(
                candidate = candidate,
                target = PublishTarget.REVIEW,
                scheduledAt = clock.instant(),
            )
        )
        logger.debug("Cluster {} queued for review", candidate.clusterKey)
        return PublishOutcomeType.REVIEW_QUEUED
    }

    private suspend fun publishBreaking(
        cluster: Cluster,
        candidate: ModerationCandidate,
        score: EventScore,
    ): PublishOutcomeType {
        val gate = antiNoisePolicy.allowBreaking()
        if (!gate.allowed) {
            logger.info(
                "Breaking suppressed for cluster {} due to {}, scheduling digest",
                cluster.clusterKey,
                gate.reason
            )
            return enqueueDigest(cluster, candidate, score)
        }
        val deepLink = candidate.deepLink
        val result = telegramPublisher.publishBreaking(cluster, deepLink)
        val published = result.result == PublishResult.CREATED || result.result == PublishResult.EDITED
        if (published) {
            publishJobQueue.enqueue(
                buildPublishJob(
                    candidate = candidate,
                    target = PublishTarget.BREAKING,
                    scheduledAt = clock.instant(),
                    status = PublishJobStatus.PUBLISHED,
                    publishedAt = clock.instant(),
                )
            )
            publishJobQueue.markPendingDigestSkipped(candidate.clusterId)
            return PublishOutcomeType.BREAKING_PUBLISHED
        }
        return PublishOutcomeType.BREAKING_FAILED
    }

    private suspend fun isMuted(sourceDomain: String, entityHashes: List<String>): Boolean {
        if (moderationQueue.isSourceMuted(sourceDomain)) {
            return true
        }
        return entityHashes.any { moderationQueue.isEntityMuted(it) }
    }

    private fun buildCandidate(
        cluster: Cluster,
        score: EventScore,
        decision: RouteDecision,
        entityHashes: List<String>,
        deepLink: String,
    ): ModerationCandidate {
        val links = cluster.articles.map { it.url }.distinct().take(5)
        val primaryEntity = entityHashes.firstOrNull()
        val suggestedMode = if (decision.route == EventRoute.PUBLISH_NOW) {
            ModerationSuggestedMode.BREAKING
        } else {
            ModerationSuggestedMode.DIGEST
        }
        return ModerationCandidate(
            clusterId = ModerationIds.clusterIdFromKey(cluster.clusterKey),
            clusterKey = cluster.clusterKey,
            suggestedMode = suggestedMode,
            score = score.score,
            confidence = score.confidence,
            links = links,
            sourceDomain = cluster.canonical.domain,
            entityHashes = entityHashes,
            primaryEntityHash = primaryEntity,
            title = cluster.canonical.title,
            summary = cluster.canonical.summary,
            topics = cluster.topics,
            deepLink = deepLink,
            createdAt = cluster.createdAt,
        )
    }

    private fun extractEntityHashes(cluster: Cluster): List<String> {
        val entities = cluster.articles.flatMap { it.entities }.map { it.trim() }.filter { it.isNotBlank() }
        return entities.map { ModerationHashes.hashEntity(it) }.distinct()
    }

    private fun buildPublishJob(
        candidate: ModerationCandidate,
        target: PublishTarget,
        scheduledAt: java.time.Instant,
        status: PublishJobStatus = PublishJobStatus.PENDING,
        publishedAt: java.time.Instant? = null,
    ): PublishJobRequest {
        return PublishJobRequest(
            clusterId = candidate.clusterId,
            clusterKey = candidate.clusterKey,
            target = target,
            scheduledAt = scheduledAt,
            title = candidate.title,
            summary = candidate.summary,
            sourceDomain = candidate.sourceDomain,
            topics = candidate.topics,
            deepLink = candidate.deepLink,
            createdAt = candidate.createdAt,
            status = status,
            publishedAt = publishedAt,
        )
    }

    private fun deepLink(cluster: Cluster): String {
        val base = config.botDeepLinkBase.trimEnd('/')
        val payload = DeepLinkPayload(
            type = DeepLinkType.TOPIC,
            id = payloadId(cluster.clusterKey),
        )
        val shortCode = deepLinkStore.put(payload, deepLinkTtl)
        return "$base?start=$shortCode"
    }

    private fun payloadId(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(key.toByteArray(Charsets.UTF_8)).copyOf(12)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private enum class PublishOutcomeType {
    BREAKING_PUBLISHED,
    BREAKING_FAILED,
    DIGEST_QUEUED,
    REVIEW_QUEUED,
    SKIPPED_MUTED,
    DROPPED,
}
