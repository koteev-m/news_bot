package news.render

import news.moderation.ModerationCandidate
import java.util.Locale
import news.moderation.ModerationSuggestedMode

object ModerationTemplates {
    fun renderAdminCard(candidate: ModerationCandidate): String {
        val builder = StringBuilder()
        builder.append("*Кандидат на публикацию*\n")
        builder.append("Источник: ")
        builder.append(PostTemplates.escapeMarkdownV2(candidate.sourceDomain))
        builder.append("\n")
        builder.append("Режим: ")
        builder.append(modeLabel(candidate.suggestedMode))
        builder.append("\n")
        builder.append("Скор: ")
        builder.append(String.format(Locale.US, "%.2f", candidate.score))
        builder.append(" | Confidence: ")
        builder.append(String.format(Locale.US, "%.2f", candidate.confidence))
        builder.append("\n")
        builder.append("Заголовок: ")
        builder.append(PostTemplates.escapeMarkdownV2(candidate.title))
        candidate.summary?.takeIf { it.isNotBlank() }?.let {
            builder.append("\n")
            builder.append("Summary: ")
            builder.append(PostTemplates.escapeMarkdownV2(it.take(240)))
        }
        if (candidate.topics.isNotEmpty()) {
            builder.append("\n")
            builder.append("Темы: ")
            builder.append(PostTemplates.escapeMarkdownV2(candidate.topics.joinToString(", ")))
        }
        builder.append("\n")
        builder.append("Ссылки: ")
        builder.append(candidate.links.size)
        builder.append("\n")
        candidate.links.take(3).forEach { link ->
            builder.append("• ")
            builder.append(PostTemplates.escapeMarkdownV2(link))
            builder.append("\n")
        }
        builder.append("Cluster: ")
        builder.append(PostTemplates.escapeMarkdownV2(candidate.clusterKey.take(32)))
        return builder.toString().trimEnd()
    }

    fun renderBreakingPost(candidate: ModerationCandidate): String {
        val builder = StringBuilder()
        builder.append("*Breaking:* ")
        builder.append(PostTemplates.escapeMarkdownV2(candidate.title))
        candidate.summary?.let {
            if (it.isNotBlank()) {
                builder.append("\n\n")
                builder.append(PostTemplates.escapeMarkdownV2(it))
            }
        }
        builder.append("\n\n")
        builder.append("Источник: ")
        builder.append(PostTemplates.escapeMarkdownV2(candidate.sourceDomain))
        builder.append("\n")
        builder.append("👉 ")
        builder.append("[Открыть в боте](")
        builder.append(PostTemplates.escapeMarkdownV2Url(candidate.deepLink))
        builder.append(")")
        return builder.toString()
    }

    fun renderEditedPost(text: String, deepLink: String): String {
        val builder = StringBuilder()
        builder.append(PostTemplates.escapeMarkdownV2(text))
        builder.append("\n\n")
        builder.append("👉 ")
        builder.append("[Открыть в боте](")
        builder.append(PostTemplates.escapeMarkdownV2Url(deepLink))
        builder.append(")")
        return builder.toString()
    }

    private fun modeLabel(mode: ModerationSuggestedMode): String {
        return when (mode) {
            ModerationSuggestedMode.BREAKING -> "breaking"
            ModerationSuggestedMode.DIGEST -> "digest"
        }
    }
}
