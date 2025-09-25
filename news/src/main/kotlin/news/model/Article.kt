package news.model

import java.time.Instant

data class Article(
    val id: String,
    val url: String,
    val domain: String,
    val title: String,
    val summary: String?,
    val publishedAt: Instant,
    val language: String?,
    val tickers: Set<String>,
    val entities: Set<String>
)

data class Cluster(
    val clusterKey: String,
    val canonical: Article,
    val articles: List<Article>,
    val topics: Set<String>,
    val createdAt: Instant
)
