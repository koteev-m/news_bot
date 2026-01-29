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
import news.config.NewsMode
import news.dedup.Clusterer
import news.model.Article
import news.model.Cluster
import news.moderation.ModerationCandidate
import news.moderation.ModerationHashes
import news.moderation.ModerationIds
import news.moderation.ModerationQueue
import news.moderation.ModerationScoreProvider
import news.moderation.ModerationScorer
import news.moderation.ModerationSuggestedMode
import news.publisher.PublishResult
import news.publisher.TelegramPublisher
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
    private val moderationScorer: ModerationScoreProvider = ModerationScorer(config),
    private val publishJobQueue: PublishJobQueue = PublishJobQueue.Noop,
    private val clock: Clock = Clock.systemUTC(),
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
            return@coroutineScope 0
        }
        val clusters = clusterer.cluster(fetchedArticles)
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
            }
        }.awaitAll().flatten()
    }

    private suspend fun handleClusters(clusters: List<Cluster>): List<PublishOutcomeType> {
        val outcomes = mutableListOf<PublishOutcomeType>()
        for (cluster in clusters.sortedByDescending { it.canonical.publishedAt }) {
            val score = moderationScorer.score(cluster)
            val suggested = moderationScorer.suggestedMode(cluster, score)
            val candidate = buildCandidate(cluster, score, suggested)
            if (isMuted(candidate)) {
                logger.info("Cluster {} skipped due to mute", cluster.clusterKey)
                outcomes += PublishOutcomeType.SKIPPED_MUTED
                continue
            }
            when (config.mode) {
                NewsMode.DIGEST_ONLY -> {
                    outcomes += enqueueDigest(cluster, candidate, score)
                }
                NewsMode.HYBRID -> {
                    if (score.tier0 && score.confidence >= config.scoring.minConfidenceAutopublish) {
                        outcomes += autoPublish(cluster, candidate, score)
                    } else {
                        outcomes += queueForReview(candidate)
                    }
                }
                NewsMode.AUTOPUBLISH -> {
                    outcomes += autoPublish(cluster, candidate, score)
                }
            }
        }
        return outcomes
    }

    private suspend fun enqueueDigest(
        cluster: Cluster,
        candidate: ModerationCandidate,
        score: ModerationScorer.ModerationScore,
    ): PublishOutcomeType {
        if (!score.digestCandidate) {
            logger.info("Cluster {} skipped due to low digest score {}", cluster.clusterKey, score.score)
            return PublishOutcomeType.SKIPPED_SCORE
        }
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

    private suspend fun autoPublish(
        cluster: Cluster,
        candidate: ModerationCandidate,
        score: ModerationScorer.ModerationScore,
    ): PublishOutcomeType {
        if (!score.digestCandidate) {
            logger.info("Cluster {} skipped due to low digest score {}", cluster.clusterKey, score.score)
            return PublishOutcomeType.SKIPPED_SCORE
        }
        val plannedTarget = if (score.breakingCandidate) PublishTarget.BREAKING else PublishTarget.DIGEST
        return if (plannedTarget == PublishTarget.BREAKING) {
            publishBreaking(cluster, candidate)
        } else {
            enqueueDigest(cluster, candidate, score)
        }
    }

    private suspend fun publishBreaking(
        cluster: Cluster,
        candidate: ModerationCandidate,
    ): PublishOutcomeType {
        val gate = antiNoisePolicy.allowBreaking()
        if (!gate.allowed) {
            logger.info(
                "Breaking suppressed for cluster {} due to {}, scheduling digest",
                cluster.clusterKey,
                gate.reason
            )
            return enqueueDigest(cluster, candidate, moderationScorer.score(cluster))
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

    private suspend fun isMuted(candidate: ModerationCandidate): Boolean {
        if (moderationQueue.isSourceMuted(candidate.sourceDomain)) {
            return true
        }
        return candidate.entityHashes.any { moderationQueue.isEntityMuted(it) }
    }

    private fun buildCandidate(
        cluster: Cluster,
        score: ModerationScorer.ModerationScore,
        suggestedMode: ModerationSuggestedMode
    ): ModerationCandidate {
        val links = cluster.articles.map { it.url }.distinct().take(5)
        val entities = cluster.articles.flatMap { it.entities }.map { it.trim() }.filter { it.isNotBlank() }
        val entityHashes = entities.map { ModerationHashes.hashEntity(it) }.distinct()
        val primaryEntity = entityHashes.firstOrNull()
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
            deepLink = deepLink(cluster),
            createdAt = cluster.createdAt,
        )
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
    SKIPPED_SCORE,
}
