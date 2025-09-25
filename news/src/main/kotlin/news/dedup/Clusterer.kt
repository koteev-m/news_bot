package news.dedup

import java.security.MessageDigest
import java.time.Instant
import news.canonical.CanonicalPicker
import news.config.NewsConfig
import news.model.Article
import news.model.Cluster
import news.nlp.TextNormalize
import kotlin.text.Charsets

class Clusterer(
    config: NewsConfig,
    private val lsh: MinHashLSH = MinHashLSH(),
    private val canonicalPicker: CanonicalPicker = CanonicalPicker(config)
) {
    private val jaccardThreshold = 0.75

    fun cluster(articles: List<Article>): List<Cluster> {
        if (articles.isEmpty()) return emptyList()
        val clusters = LinkedHashMap<String, MutableCluster>()
        val fingerprintIndex = mutableMapOf<String, String>()
        val entityIndex = mutableMapOf<String, MutableSet<String>>()
        val bucketIndex = mutableMapOf<String, MutableSet<String>>()

        for (article in articles.sortedBy { it.publishedAt }) {
            val tokens = TextNormalize.tokensForShingles(article.title, article.summary)
            val shingles = Shingles.generate(tokens)
            val simHash = SimHash64.hash(shingles)
            val signature = lsh.signature(shingles)
            val articleEntities = article.entities + article.tickers
            val fastHash = fastHash(article.title, article.domain)

            val existingKey = fingerprintIndex[fastHash]
            val candidateKeys = LinkedHashSet<String>()
            if (existingKey != null) {
                candidateKeys.add(existingKey)
            }
            for (entity in articleEntities) {
                entityIndex[entity]?.let { candidateKeys.addAll(it) }
            }
            for (bucket in lsh.bucketKeys(signature)) {
                bucketIndex[bucket]?.let { candidateKeys.addAll(it) }
            }

            val matchedCluster = candidateKeys.firstNotNullOfOrNull { key ->
                val candidate = clusters[key] ?: return@firstNotNullOfOrNull null
                if (articleEntities.isNotEmpty() && candidate.entities.intersect(articleEntities).isNotEmpty()) {
                    candidate
                } else {
                    val hamDist = SimHash64.hammingDistance(candidate.simHash, simHash)
                    if (hamDist <= 3) {
                        candidate
                    } else {
                        val jaccard = lsh.jaccardSimilarity(candidate.shingles, shingles)
                        if (jaccard >= jaccardThreshold) candidate else null
                    }
                }
            }

            if (matchedCluster != null) {
                val updatedSignature = matchedCluster.addArticle(article, shingles)
                fingerprintIndex[fastHash] = matchedCluster.key
                for (entity in articleEntities) {
                    entityIndex.getOrPut(entity) { mutableSetOf() }.add(matchedCluster.key)
                }
                for (bucket in lsh.bucketKeys(updatedSignature)) {
                    bucketIndex.getOrPut(bucket) { mutableSetOf() }.add(matchedCluster.key)
                }
            } else {
                val clusterKey = clusterKey(article)
                val mutableCluster = MutableCluster(
                    key = clusterKey,
                    canonical = article,
                    articles = mutableListOf(article),
                    shingles = shingles.toMutableSet(),
                    simHash = simHash,
                    signature = signature,
                    entities = articleEntities.toMutableSet(),
                    topics = (articleEntities).toMutableSet(),
                    createdAt = article.publishedAt
                )
                clusters[clusterKey] = mutableCluster
                fingerprintIndex[fastHash] = clusterKey
                for (entity in articleEntities) {
                    entityIndex.getOrPut(entity) { mutableSetOf() }.add(clusterKey)
                }
                for (bucket in lsh.bucketKeys(signature)) {
                    bucketIndex.getOrPut(bucket) { mutableSetOf() }.add(clusterKey)
                }
            }
        }

        return clusters.values.map { it.toCluster(canonicalPicker) }
    }

    private fun fastHash(title: String, domain: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val normalized = "${domain.lowercase()}|${title.lowercase()}"
        val bytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { String.format("%02x", it) }
    }

    private fun clusterKey(article: Article): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val seed = "${article.domain}|${article.id}|${article.title.lowercase()}"
        val bytes = digest.digest(seed.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { String.format("%02x", it) }
    }

    private inner class MutableCluster(
        val key: String,
        var canonical: Article,
        val articles: MutableList<Article>,
        val shingles: MutableSet<String>,
        var simHash: Long,
        var signature: IntArray,
        val entities: MutableSet<String>,
        val topics: MutableSet<String>,
        var createdAt: Instant
    ) {
        fun addArticle(article: Article, articleShingles: Set<String>): IntArray {
            articles.add(article)
            entities.addAll(article.entities)
            entities.addAll(article.tickers)
            topics.addAll(article.entities)
            topics.addAll(article.tickers)
            shingles.addAll(articleShingles)
            simHash = SimHash64.hash(shingles)
            signature = lsh.signature(shingles)
            canonical = canonicalPicker.choose(canonical, article)
            if (article.publishedAt.isBefore(createdAt)) {
                createdAt = article.publishedAt
            }
            return signature
        }

        fun toCluster(picker: CanonicalPicker): Cluster {
            val canonicalArticle = articles.fold<Article, Article?>(null) { acc, item ->
                picker.choose(acc, item)
            } ?: canonical
            return Cluster(
                clusterKey = key,
                canonical = canonicalArticle,
                articles = articles.sortedBy { it.publishedAt },
                topics = topics.toSet(),
                createdAt = createdAt
            )
        }
    }
}
