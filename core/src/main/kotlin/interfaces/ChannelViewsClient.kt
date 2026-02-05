package interfaces

interface ChannelViewsClient {
    suspend fun getViews(
        channel: String,
        ids: List<Int>,
        increment: Boolean = false,
    ): Map<Int, Long>
}
