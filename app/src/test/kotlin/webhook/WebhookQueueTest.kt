package webhook

import billing.TgUpdate
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test
    fun `stop timer once on cancellation`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = WebhookMetrics.create(registry)
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() + exceptionHandler)
        val queue = WebhookQueue(
            capacity = 1,
            mode = OverflowMode.DROP_LATEST,
            workers = 1,
            scope = scope,
            metrics = metrics
        ) { _ ->
            throw kotlinx.coroutines.CancellationException("cancel")
        }

        queue.start()
        queue.offer(TgUpdate())

        advanceUntilIdle()
        queue.shutdown(1.seconds)

        assertEquals(1L, metrics.handleTimer.count())
    }

    @Test
    fun `stop timer once on error`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = WebhookMetrics.create(registry)
        var captured: Throwable? = null
        val exceptionHandler = CoroutineExceptionHandler { _, throwable -> captured = throwable }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() + exceptionHandler)
        val queue = WebhookQueue(
            capacity = 1,
            mode = OverflowMode.DROP_LATEST,
            workers = 1,
            scope = scope,
            metrics = metrics
        ) { _ ->
            throw AssertionError("boom")
        }

        queue.start()
        queue.offer(TgUpdate())

        advanceUntilIdle()
        queue.shutdown(1.seconds)

        assertEquals(1L, metrics.handleTimer.count())
        assertTrue(captured is AssertionError)
    }
}
