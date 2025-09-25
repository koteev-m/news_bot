package news.render

import news.model.Cluster

object PostTemplates {
    fun renderBreaking(cluster: Cluster, deepLink: String): String {
        val canonical = cluster.canonical
        val builder = StringBuilder()
        builder.append("*Breaking:* ")
        builder.append(escapeMarkdown(canonical.title))
        canonical.summary?.let {
            builder.append("\n\n")
            builder.append(escapeMarkdown(it))
        }
        builder.append("\n\n")
        builder.append("Источник: ")
        builder.append(escapeMarkdown(canonical.domain))
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
            builder.append(escapeMarkdown(cluster.canonical.title))
            cluster.topics.takeIf { it.isNotEmpty() }?.let {
                builder.append(" — ")
                builder.append(escapeMarkdown(it.joinToString(", ")))
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
            builder.append(escapeMarkdown(cluster.canonical.title))
            if (cluster.topics.isNotEmpty()) {
                builder.append(" (")
                builder.append(escapeMarkdown(cluster.topics.joinToString(", ")))
                builder.append(")")
            }
        }
        builder.append("\n\n")
        builder.append("Подробнее: ")
        builder.append(renderCta(deepLinkBuilder(clusters.firstOrNull() ?: return builder.toString().trim())))
        return builder.toString().trim()
    }

    private fun renderCta(deepLink: String): String {
        val sanitized = deepLink.take(64)
        return "👉 [Открыть в боте](${escapeMarkdownLink(sanitized)})"
    }

    private fun escapeMarkdown(text: String): String {
        return buildString(text.length) {
            text.forEach { char ->
                when (char) {
                    '\\', '*', '_', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!' -> append('\\')
                }
                append(char)
            }
        }
    }

    private fun escapeMarkdownLink(text: String): String {
        return text.replace("(", "%28").replace(")", "%29").replace(" ", "%20")
    }
}
