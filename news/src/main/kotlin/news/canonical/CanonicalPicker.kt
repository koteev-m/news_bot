package news.canonical

import news.config.NewsConfig
import news.config.NewsDefaults
import news.model.Article

class CanonicalPicker(private val config: NewsConfig) {
    private val weightMap: Map<String, Int> = config.sourceWeights.associate { it.domain.lowercase() to it.weight }

    fun choose(current: Article?, candidate: Article): Article {
        if (current == null) return candidate
        val currentWeight = weightFor(current.domain)
        val candidateWeight = weightFor(candidate.domain)
        return when {
            candidateWeight > currentWeight -> candidate
            candidateWeight < currentWeight -> current
            candidate.publishedAt.isBefore(current.publishedAt) -> candidate
            else -> current
        }
    }

    fun weightFor(domain: String): Int {
        val normalized = domain.lowercase()
        return weightMap[normalized] ?: NewsDefaults.weightFor(normalized, config.sourceWeights)
    }
}
