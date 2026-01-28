package news.pipeline

import deeplink.DeepLinkPayload
import deeplink.DeepLinkStore
import deeplink.DeepLinkType
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import news.canonical.CanonicalPicker
import news.config.NewsConfig
import news.dedup.Clusterer
import news.model.Article
import news.model.Cluster
import news.moderation.ModerationCandidate
import news.moderation.ModerationHashes
import news.moderation.ModerationIds
import news.moderation.ModerationQueue
import news.moderation.ModerationScorer
import news.moderation.ModerationSuggestedMode
import news.publisher.IdempotencyStore
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
    private val idempotencyStore: IdempotencyStore,
    private val deepLinkStore: DeepLinkStore,
    private val deepLinkTtl: Duration,
    private val moderationQueue: ModerationQueue = ModerationQueue.Noop,
    private val moderationScorer: ModerationScorer = ModerationScorer(config),
) {
    private val logger = LoggerFactory.getLogger(NewsPipeline::class.java)
    private val canonicalPicker = CanonicalPicker(config)

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
        val moderatedClusters = applyModeration(clusters)
        if (config.modeDigestOnly) {
            val posted = telegramPublisher.publishDigest(moderatedClusters, ::deepLink)
            if (posted) {
                logger.info("Published digest post")
                return@coroutineScope 1
            }
            logger.info("Digest post was not published")
            return@coroutineScope 0
        }
        if (!config.modeAutopublishBreaking) {
            logger.info("Breaking autopublish disabled")
            return@coroutineScope 0
        }
        val breakingCandidates = selectBreaking(moderatedClusters)
            .filterNot { idempotencyStore.seen(it.clusterKey) }
        var published = 0
        for (cluster in breakingCandidates) {
            val deepLink = deepLink(cluster)
            val posted = telegramPublisher.publishBreaking(cluster, deepLink)
            if (posted) {
                published += 1
            }
        }
        logger.info("Published {} breaking posts", published)
        published
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

    private fun selectBreaking(clusters: List<Cluster>): List<Cluster> {
        val sorted = clusters.sortedWith(
            compareByDescending<Cluster> { canonicalPicker.weightFor(it.canonical.domain) }
                .thenByDescending { it.canonical.publishedAt }
        )
        return sorted.take(2)
    }

    private suspend fun applyModeration(clusters: List<Cluster>): List<Cluster> {
        if (!config.moderationEnabled) {
            return clusters
        }
        val allowed = mutableListOf<Cluster>()
        for (cluster in clusters) {
            val score = moderationScorer.score(cluster)
            val suggested = moderationScorer.suggestedMode(cluster, score)
            val candidate = buildCandidate(cluster, score, suggested)
            if (isMuted(candidate)) {
                logger.info("Cluster {} skipped due to mute", cluster.clusterKey)
                continue
            }
            if (score.tier0 && score.confident) {
                allowed.add(cluster)
            } else {
                moderationQueue.enqueue(candidate)
            }
        }
        return allowed
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
