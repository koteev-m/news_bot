package views

import interfaces.ChannelViewsClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PostViewsServiceTest {
    @Test
    fun `sync increments post views metric per post`() = runTest {
        val registry = SimpleMeterRegistry()
        val client = CapturingViewsClient(mapOf(1 to 10L, 2 to 0L, 3 to 5L))
        val service = PostViewsService(client, registry)

        val result = service.sync("@news", listOf(1, 2, 3))

        assertEquals(mapOf(1 to 10L, 2 to 0L, 3 to 5L), result)
        assertEquals(false, client.increment)
        assertEquals(10.0, registry.get("post_views_total").tag("post_id", "1").counter().count())
        assertEquals(5.0, registry.get("post_views_total").tag("post_id", "3").counter().count())
    }
}

private class CapturingViewsClient(private val response: Map<Int, Long>) : ChannelViewsClient {
    var increment: Boolean? = null

    override suspend fun getViews(channel: String, ids: List<Int>, increment: Boolean): Map<Int, Long> {
        this.increment = increment
        return response
    }
}
