package news.classification

import news.model.Cluster
import news.model.EventType

data class EventCandidate(
    val eventType: EventType,
    val mainEntity: String?,
    val confidence: Double,
)

class EventClassifier {
    fun classify(cluster: Cluster): EventCandidate {
        val canonical = cluster.canonical
        val title = canonical.title
        val summary = canonical.summary.orEmpty()
        val text = "$title $summary".lowercase()
        val domain = canonical.domain.lowercase()
        val mainEntity = selectMainEntity(cluster)

        val candidate = when {
            isCbrRate(domain, text) -> EventCandidate(EventType.CBR_RATE, mainEntity, 0.9)
            isMoexTradingStatus(domain, text) -> EventCandidate(EventType.MOEX_TRADING_STATUS, mainEntity, 0.88)
            isListingDelisting(text) -> EventCandidate(EventType.LISTING_DELISTING, mainEntity, 0.75)
            isCbrStatement(domain, text) -> EventCandidate(EventType.CBR_STATEMENT, mainEntity, 0.7)
            isCorporateAction(text) -> EventCandidate(EventType.CORPORATE_ACTION, mainEntity, 0.65)
            else -> EventCandidate(EventType.MARKET_NEWS, mainEntity, 0.45)
        }
        return candidate.copy(confidence = candidate.confidence.coerceIn(0.0, 1.0))
    }

    private fun selectMainEntity(cluster: Cluster): String? {
        val tickers = cluster.articles
            .flatMap { it.tickers }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val entities = cluster.articles
            .flatMap { it.entities }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return mostFrequent(tickers) ?: mostFrequent(entities)
    }

    private fun mostFrequent(values: List<String>): String? {
        if (values.isEmpty()) return null
        return values.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    private fun isCbrRate(domain: String, text: String): Boolean {
        if (!domain.contains("cbr.ru")) return false
        return text.contains("ключев") && text.contains("ставк")
    }

    private fun isCbrStatement(domain: String, text: String): Boolean {
        if (!domain.contains("cbr.ru")) return false
        return text.contains("заявлен") ||
            text.contains("сообщ") ||
            text.contains("пресс-релиз") ||
            text.contains("выступ")
    }

    private fun isMoexTradingStatus(domain: String, text: String): Boolean {
        if (!domain.contains("moex.com")) return false
        val trading = text.contains("торг")
        val status = text.contains("приостан") || text.contains("возобнов") || text.contains("останов")
        return trading && status
    }

    private fun isListingDelisting(text: String): Boolean {
        return text.contains("листинг") ||
            text.contains("делистинг") ||
            text.contains("включен в список") ||
            text.contains("исключен из списка")
    }

    private fun isCorporateAction(text: String): Boolean {
        return text.contains("дивиденд") ||
            text.contains("куп") ||
            text.contains("buyback") ||
            text.contains("сплит")
    }
}
