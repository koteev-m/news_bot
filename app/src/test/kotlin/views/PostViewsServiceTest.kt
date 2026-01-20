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
        val client = CapturingViewsClient(
            listOf(
                mapOf(1 to 10L, 2 to 0L, 3 to 5L),
                mapOf(1 to 10L, 2 to 3L, 3 to 7L)
            )
        )
        val service = PostViewsService(client, registry)

        val first = service.sync("@news", listOf(1, 2, 3))
        val second = service.sync("@news", listOf(1, 2, 3))

        assertEquals(mapOf(1 to 10L, 2 to 0L, 3 to 5L), first)
        assertEquals(mapOf(1 to 10L, 2 to 3L, 3 to 7L), second)
        assertEquals(false, client.increment)
        assertEquals(10.0, registry.get("post_views_total").tag("post_id", "1").counter().count())
        assertEquals(3.0, registry.get("post_views_total").tag("post_id", "2").counter().count())
        assertEquals(7.0, registry.get("post_views_total").tag("post_id", "3").counter().count())
    }
}

private class CapturingViewsClient(private val responses: List<Map<Int, Long>>) : ChannelViewsClient {
    var increment: Boolean? = null
    private var index = 0

    override suspend fun getViews(channel: String, ids: List<Int>, increment: Boolean): Map<Int, Long> {
        this.increment = increment
        val response = responses.getOrElse(index) { responses.last() }
        index += 1
        return response
    }
}
