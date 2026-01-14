package netflow2

import data.moex.Netflow2Client
import data.moex.Netflow2ClientError
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import observability.feed.Netflow2Metrics
import repo.Netflow2Repository

data class Netflow2LoadResult(
    val sec: String,
    val from: LocalDate,
    val till: LocalDate,
    val windows: Int,
    val rowsFetched: Int,
    val rowsUpserted: Int,
    val maxDate: LocalDate?,
)

class Netflow2Loader(
    private val client: Netflow2Client,
    private val repository: Netflow2Repository,
    private val metrics: Netflow2Metrics,
) {
    suspend fun upsert(sec: String, from: LocalDate, till: LocalDate): Netflow2LoadResult {
        val startNanos = System.nanoTime()
        var maxDate: LocalDate? = null
        var cancelled = false
        try {
            if (sec.isBlank()) {
                throw Netflow2LoaderError.ValidationError("sec must not be blank")
            }
            if (from.isAfter(till)) {
                throw Netflow2LoaderError.ValidationError("from must not be after till")
            }

            val normalizedTicker = try {
                normalizeTicker(sec)
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Error) {
                throw err
            } catch (ex: Throwable) {
                val message = ex.message ?: "invalid ticker"
                throw Netflow2LoaderError.ValidationError("sec: $message")
            }
            val windows = Netflow2PullWindow.splitInclusive(from, till)
            var fetchedRows = 0
            var upsertedRows = 0

            for (window in windows) {
                val rows = try {
                    client.fetchWindow(normalizedTicker, window).getOrElse { throw it }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Error) {
                    throw err
                } catch (ex: Throwable) {
                    metrics.pullError.increment()
                    throw ex
                }

                val normalizedRows = rows.map { it.copy(ticker = normalizedTicker) }
                fetchedRows += normalizedRows.size

                try {
                    if (normalizedRows.isNotEmpty()) {
                        repository.upsert(normalizedRows)
                        upsertedRows += normalizedRows.size
                        val windowMax = normalizedRows.maxOf { it.date }
                        maxDate = maxDate?.let { existing -> maxOf(existing, windowMax) } ?: windowMax
                    }
                    metrics.pullSuccess.increment()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Error) {
                    throw err
                } catch (ex: Throwable) {
                    metrics.pullError.increment()
                    throw ex
                }
            }

            return Netflow2LoadResult(
                sec = normalizedTicker,
                from = from,
                till = till,
                windows = windows.size,
                rowsFetched = fetchedRows,
                rowsUpserted = upsertedRows,
                maxDate = maxDate,
            )
        } catch (ce: CancellationException) {
            cancelled = true
            throw ce
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            throw when (ex) {
                is Netflow2LoaderError -> ex
                is Netflow2ClientError -> ex
                else -> Netflow2LoaderError.PullFailed(ex)
            }
        } finally {
            if (!cancelled) {
                val elapsed = System.nanoTime() - startNanos
                metrics.pullLatency.record(Duration.ofNanos(elapsed))
                maxDate?.let { metrics.updateLastTimestamp(it.atStartOfDay(ZoneOffset.UTC).toEpochSecond()) }
            }
        }
    }
}

sealed class Netflow2LoaderError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class ValidationError(details: String) : Netflow2LoaderError(details)
    class PullFailed(cause: Throwable) : Netflow2LoaderError(cause.message ?: "netflow2 pull failed", cause)
}
