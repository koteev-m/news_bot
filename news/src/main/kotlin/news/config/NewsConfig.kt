package news.config

data class SourceWeight(
    val domain: String,
    val weight: Int
)

data class NewsConfig(
    val userAgent: String,
    val httpTimeoutMs: Long,
    val sourceWeights: List<SourceWeight>,
    val channelId: Long,
    val botDeepLinkBase: String,
    val maxPayloadBytes: Int = 64,
    val modeDigestOnly: Boolean,
    val modeAutopublishBreaking: Boolean
)

object NewsDefaults {
    private val defaultWeights = listOf(
        SourceWeight("cbr.ru", 100),
        SourceWeight("moex.com", 95),
        SourceWeight("interfax.ru", 70),
        SourceWeight("ria.ru", 65),
        SourceWeight("vedomosti.ru", 60),
        SourceWeight("rbk.ru", 55),
        SourceWeight("kommersant.ru", 50)
    )

    val defaultConfig: NewsConfig = NewsConfig(
        userAgent = "news-bot/1.0",
        httpTimeoutMs = 30_000,
        sourceWeights = defaultWeights,
        channelId = 0L,
        botDeepLinkBase = "https://t.me/example_bot",
        maxPayloadBytes = 64,
        modeDigestOnly = true,
        modeAutopublishBreaking = false
    )

    fun weightFor(domain: String, weights: List<SourceWeight> = defaultWeights): Int {
        return weights.firstOrNull { domain.endsWith(it.domain, ignoreCase = true) }?.weight ?: 0
    }
}
