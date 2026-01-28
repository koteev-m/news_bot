package news.moderation

data class ModerationBotConfig(
    val enabled: Boolean,
    val adminChatId: Long,
    val adminThreadId: Long?,
    val muteHours: Long,
)
