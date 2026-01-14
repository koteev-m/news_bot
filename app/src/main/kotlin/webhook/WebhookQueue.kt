package webhook

import billing.TgUpdate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import observability.WebhookMetrics
import org.slf4j.LoggerFactory

enum class OverflowMode { DROP_OLDEST, DROP_LATEST }

class WebhookQueue(
    private val capacity: Int,
    private val mode: OverflowMode,
    private val workers: Int,
    private val scope: CoroutineScope,
    private val metrics: WebhookMetrics,
    private val handler: suspend (TgUpdate) -> Unit
) {
    private val logger = LoggerFactory.getLogger(WebhookQueue::class.java)
    private val channel: Channel<TgUpdate> = Channel(capacity)
    private val started = AtomicBoolean(false)
    private val jobs = mutableListOf<Job>()

    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(workers > 0) { "workers must be > 0" }
    }

    fun offer(update: TgUpdate): Boolean {
        val result = channel.trySend(update)
        if (result.isSuccess) {
            metrics.queueSize.incrementAndGet()
            return true
        }
        if (result.isClosed) {
            metrics.dropped.increment()
            return false
        }
        return when (mode) {
            OverflowMode.DROP_LATEST -> {
                metrics.dropped.increment()
                false
            }
            OverflowMode.DROP_OLDEST -> handleDropOldest(update)
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        repeat(workers) {
            val job = scope.launch {
                for (update in channel) {
                    decrementQueueSize()
                    val sample = metrics.startSample()
                    try {
                        handler(update)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (err: Error) {
                        throw err
                    } catch (t: Throwable) {
                        logger.error("webhook-queue worker failed", t)
                    } finally {
                        metrics.processed.increment()
                        sample.stop(metrics.handleTimer)
                    }
                }
            }
            jobs += job
        }
    }

    suspend fun shutdown(timeout: Duration) {
        channel.close()
        val completed = withTimeoutOrNull(timeout) {
            jobs.forEach { it.join() }
            true
        }
        if (completed != true) {
            jobs.forEach { it.cancel() }
        }
        metrics.queueSize.set(0)
    }

    private fun handleDropOldest(update: TgUpdate): Boolean {
        val removed = channel.tryReceive().getOrNull()
        return if (removed != null) {
            decrementQueueSize()
            metrics.dropped.increment()
            val retry = channel.trySend(update)
            if (retry.isSuccess) {
                metrics.queueSize.incrementAndGet()
                true
            } else {
                metrics.dropped.increment()
                false
            }
        } else {
            metrics.dropped.increment()
            false
        }
    }

    private fun decrementQueueSize() {
        metrics.queueSize.updateAndGet { current -> max(0, current - 1) }
    }
}
