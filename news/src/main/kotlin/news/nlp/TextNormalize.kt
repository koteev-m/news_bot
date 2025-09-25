package news.nlp

import java.net.URI

object TextNormalize {
    private val htmlTagRegex = "<[^>]+>".toRegex(RegexOption.IGNORE_CASE)
    private val whitespaceRegex = "\\s+".toRegex()
    private val punctuationRegex = "[^\\p{L}\\p{Nd}\\s]".toRegex()
    private val tickerRegex = "\\b[A-Z]{3,5}\\b".toRegex()
    private val htmlEntities = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&lt;" to "<",
        "&gt;" to ">"
    )
    private val domainEntities = mapOf(
        "cbr.ru" to setOf("Bank of Russia"),
        "www.cbr.ru" to setOf("Bank of Russia"),
        "moex.com" to setOf("Moscow Exchange"),
        "www.moex.com" to setOf("Moscow Exchange")
    )
    private val moexWhitelist = setOf("GAZP", "SBER", "LKOH", "VTBR", "ALRS", "GMKN")

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw!!
        htmlEntities.forEach { (key, value) ->
            text = text.replace(key, value, ignoreCase = true)
        }
        text = text.replace(htmlTagRegex, " ")
        text = text.replace(punctuationRegex, " ")
        text = text.lowercase()
        text = text.replace(whitespaceRegex, " ").trim()
        return text
    }

    fun combineText(vararg parts: String?): String {
        return parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
    }

    fun tokensForShingles(title: String, summary: String?): List<String> {
        val combined = clean(combineText(title, summary))
        if (combined.isEmpty()) return emptyList()
        return combined.split(' ').filter { it.isNotBlank() }
    }

    fun extractTickers(text: String, domain: String): Set<String> {
        val matches = tickerRegex.findAll(text).map { it.value }.toSet()
        if (matches.isEmpty()) return emptySet()
        val normalizedDomain = URI("https://$domain").host.lowercase()
        return if (normalizedDomain.contains("moex")) {
            matches.filter { moexWhitelist.contains(it) }.toSet()
        } else {
            matches
        }
    }

    fun extractEntities(domain: String, tickers: Set<String>): Set<String> {
        val normalizedDomain = domain.lowercase()
        val baseEntities = domainEntities[normalizedDomain] ?: emptySet()
        return baseEntities + tickers
    }
}
