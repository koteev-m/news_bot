package webhook

import billing.TgUpdate
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import observability.WebhookMetrics

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookQueueTest {

    @Test
    fun `capacity overflow drops updates and counts processed`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = WebhookMetrics.create(registry)
        val processed = AtomicInteger(0)
        val queue = WebhookQueue(
            capacity = 3,
            mode = OverflowMode.DROP_LATEST,
            workers = 1,
            scope = this,
            metrics = metrics
        ) { _ ->
            processed.incrementAndGet()
        }

        queue.start()
        repeat(5) { queue.offer(TgUpdate()) }

        advanceUntilIdle()
        queue.shutdown(1.seconds)

        assertEquals(3, processed.get())
        assertEquals(3.0, metrics.processed.count(), 0.0001)
        assertTrue(metrics.dropped.count() >= 2.0)
    }

    @Test
    fun `multiple workers consume updates`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = WebhookMetrics.create(registry)
        val processed = AtomicInteger(0)
        val queue = WebhookQueue(
            capacity = 4,
            mode = OverflowMode.DROP_LATEST,
            workers = 2,
            scope = this,
            metrics = metrics
        ) { _ ->
            processed.incrementAndGet()
        }

        queue.start()
        repeat(4) { queue.offer(TgUpdate()) }

        advanceUntilIdle()
        queue.shutdown(1.seconds)

        assertEquals(4, processed.get())
        assertEquals(4.0, metrics.processed.count(), 0.0001)
        assertEquals(0.0, metrics.dropped.count(), 0.0001)
    }
}
