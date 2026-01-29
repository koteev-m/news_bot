package news.publisher

enum class PublishResult {
    CREATED,
    EDITED,
    SKIPPED,
    FAILED,
}

data class PublishOutcome(
    val result: PublishResult,
    val messageId: Long? = null,
)
