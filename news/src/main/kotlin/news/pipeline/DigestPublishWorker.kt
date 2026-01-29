package news.pipeline

import java.time.Clock
import java.time.Instant
import news.model.Article
import news.model.Cluster
import news.publisher.PublishResult
import news.publisher.TelegramPublisher
import org.slf4j.LoggerFactory

class DigestPublishWorker(
    private val queue: PublishJobQueue,
    private val publisher: TelegramPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 50,
    private val instanceId: String,
) {
    private val logger = LoggerFactory.getLogger(DigestPublishWorker::class.java)

    suspend fun runOnce(): Int {
        val now = clock.instant()
        val jobs = queue.claimDueDigestJobs(now, batchSize, instanceId)
        if (jobs.isEmpty()) return 0
        val orderedJobs = jobs.sortedByDescending { it.createdAt }
        val deepLinks = orderedJobs.associateBy({ it.clusterKey }, { it.deepLink })
        val clusters = orderedJobs.map { it.toCluster() }
        val outcome = publisher.publishDigest(clusters) { cluster ->
            deepLinks[cluster.clusterKey] ?: ""
        }
        return when (outcome.result) {
            PublishResult.CREATED,
            PublishResult.EDITED,
            PublishResult.SKIPPED -> {
                queue.markJobsStatus(
                    orderedJobs.map { it.jobId },
                    PublishJobStatus.PUBLISHED,
                    now
                )
                orderedJobs.size
            }
            PublishResult.FAILED -> {
                queue.markJobsStatus(orderedJobs.map { it.jobId }, PublishJobStatus.FAILED)
                logger.warn("Digest publish failed for {} jobs", orderedJobs.size)
                0
            }
        }
    }
}

private fun PublishJob.toCluster(): Cluster {
    val article = Article(
        id = clusterKey,
        url = "",
        domain = sourceDomain,
        title = title,
        summary = summary,
        publishedAt = createdAt,
        language = "ru",
        tickers = emptySet(),
        entities = emptySet(),
    )
    return Cluster(
        clusterKey = clusterKey,
        canonical = article,
        articles = listOf(article),
        topics = topics,
        createdAt = createdAt,
    )
}
