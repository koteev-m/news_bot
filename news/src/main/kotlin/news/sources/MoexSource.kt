package news.sources

import java.net.URI
import java.time.Instant
import news.model.Article
import news.nlp.TextNormalize
import news.rss.RssFetcher
import news.rss.RssItem
import common.runCatchingNonFatal

class MoexSource(
    private val fetcher: RssFetcher,
    private val feedUrl: String = "https://www.moex.com/export/news.aspx?type=official&format=rss"
) : NewsSource {
    override val name: String = "moex.com"

    override suspend fun fetch(): List<Article> {
        return fetcher.fetchRss(feedUrl).map { it.toArticle() }
    }

    private fun RssItem.toArticle(): Article {
        val linkDomain = runCatchingNonFatal { URI(link).host }.getOrNull() ?: name
        val rawSummary = description ?: content ?: ""
        val combinedText = TextNormalize.combineText(title, rawSummary)
        val tickers = TextNormalize.extractTickers(combinedText, linkDomain)
        val cleanedSummary = TextNormalize.clean(rawSummary)
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
