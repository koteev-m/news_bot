package news.pipeline

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
import news.publisher.IdempotencyStore
import news.publisher.TelegramPublisher
import news.sources.NewsSource
import org.slf4j.LoggerFactory
import kotlin.text.Charsets

class NewsPipeline(
    private val config: NewsConfig,
    private val sources: List<NewsSource>,
    private val clusterer: Clusterer,
    private val telegramPublisher: TelegramPublisher,
    private val idempotencyStore: IdempotencyStore
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
        val breakingCandidates = selectBreaking(clusters)
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
                runCatching { source.fetch() }
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

    private fun deepLink(cluster: Cluster): String {
        val base = config.botDeepLinkBase.trimEnd('/')
        val payload = payload(cluster.clusterKey)
        return "$base?start=$payload"
    }

    private fun payload(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(key.toByteArray(Charsets.UTF_8)).copyOf(12)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
