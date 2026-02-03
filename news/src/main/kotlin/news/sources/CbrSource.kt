package news.sources

import java.net.URI
import java.time.Instant
import news.model.Article
import news.nlp.TextNormalize
import news.rss.RssFetcher
import news.rss.RssItem
import common.runCatchingNonFatal

interface NewsSource {
    val name: String
    suspend fun fetch(): List<Article>
}

class CbrSource(
    private val fetcher: RssFetcher,
    private val feedUrl: String = "https://www.cbr.ru/rss/RSS_press.xml"
) : NewsSource {
    override val name: String = "cbr.ru"

    override suspend fun fetch(): List<Article> {
        return fetcher.fetchRss(name, feedUrl).map { it.toArticle() }
    }

    private fun RssItem.toArticle(): Article {
        val linkDomain = runCatchingNonFatal { URI(link).host }.getOrNull() ?: name
        val cleanedSummary = TextNormalize.clean(description ?: content)
        val tickers = emptySet<String>()
        val entities = TextNormalize.extractEntities(linkDomain, tickers)
        return Article(
            id = id,
            url = link,
            domain = linkDomain,
            title = title.trim(),
            summary = cleanedSummary.ifBlank { null },
            publishedAt = publishedAt ?: Instant.now(),
            language = "ru",
            tickers = tickers,
            entities = entities
        )
    }
}
