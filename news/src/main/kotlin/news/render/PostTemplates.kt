package news.render

import news.model.Cluster

object PostTemplates {
    fun renderBreaking(cluster: Cluster, deepLink: String): String {
        val canonical = cluster.canonical
        val builder = StringBuilder()
        builder.append("*Breaking:* ")
        builder.append(escapeMarkdownV2(canonical.title))
        canonical.summary?.let {
            if (it.isNotBlank()) {
                builder.append("\n\n")
                builder.append(escapeMarkdownV2(it))
            }
        }
        builder.append("\n\n")
        builder.append("Источник: ")
        builder.append(escapeMarkdownV2(canonical.domain))
        builder.append("\n")
        builder.append(renderCta(deepLink))
        return builder.toString()
    }

    fun renderDigest(clusters: List<Cluster>, deepLinkBuilder: (Cluster) -> String): String {
        val builder = StringBuilder()
        builder.append("*Daily Digest*\n")
        clusters.take(3).forEachIndexed { index, cluster ->
            builder.append("\n")
            builder.append("${index + 1}. ")
            builder.append(escapeMarkdownV2(cluster.canonical.title))
            cluster.topics.takeIf { it.isNotEmpty() }?.let {
                builder.append(" — ")
                builder.append(escapeMarkdownV2(it.joinToString(", ")))
            }
            builder.append("\n")
            builder.append(renderCta(deepLinkBuilder(cluster)))
            builder.append("\n")
        }
        return builder.toString().trimEnd()
    }

    fun renderWeeklyPreview(clusters: List<Cluster>, deepLinkBuilder: (Cluster) -> String): String {
        val builder = StringBuilder()
        builder.append("*Weekly Preview*\n")
        clusters.forEach { cluster ->
            builder.append("\n• ")
            builder.append(escapeMarkdownV2(cluster.canonical.title))
            if (cluster.topics.isNotEmpty()) {
                builder.append(" (")
                builder.append(escapeMarkdownV2(cluster.topics.joinToString(", ")))
                builder.append(")")
            }
        }
        if (clusters.isNotEmpty()) {
            builder.append("\n\n")
            builder.append("Подробнее: ")
            builder.append(renderCta(deepLinkBuilder(clusters.first())))
        }
        return builder.toString().trim()
    }

    fun escapeMarkdownV2(text: String): String {
        return buildString(text.length * 2) {
            text.forEach { char ->
                when (char) {
                    '_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!' -> append(
                        '\\'
                    )
                }
                append(char)
            }
        }
    }

    fun escapeMarkdownV2Url(url: String): String {
        return buildString(url.length * 2) {
            url.forEach { char ->
                when (char) {
                    '(', ')', '\\' -> append('\\')
                }
                append(char)
            }
        }
    }

    private fun renderCta(deepLink: String): String {
        val url = escapeMarkdownV2Url(deepLink)
        return "👉 [Открыть в боте]($url)"
    }
}
