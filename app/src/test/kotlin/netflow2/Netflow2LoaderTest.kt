package netflow2

import data.moex.Netflow2Client
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import observability.feed.Netflow2Metrics
import repo.Netflow2Repository
import java.time.ZoneOffset

class Netflow2LoaderTest {
    @Test
    fun `splits range into windows and upserts sequentially`() = runTest {
        val metrics = Netflow2Metrics(SimpleMeterRegistry())
        val client = io.mockk.mockk<Netflow2Client>()
        val repository = io.mockk.mockk<Netflow2Repository>()
        val loader = Netflow2Loader(client, repository, metrics)

        val from = LocalDate.of(2018, 1, 1)
        val till = LocalDate.of(2021, 1, 2)
        val windows = Netflow2PullWindow.splitInclusive(from, till)

        val firstRows = listOf(
            Netflow2Row(
                date = LocalDate.of(2018, 1, 1),
                ticker = "SBER",
                p30 = 1,
                p70 = null,
                p100 = null,
                pv30 = null,
                pv70 = null,
                pv100 = null,
                vol = null,
                oi = null
            )
        )
        val secondRows = listOf(
            Netflow2Row(
                date = LocalDate.of(2021, 1, 1),
                ticker = "SBER",
                p30 = null,
                p70 = 2,
                p100 = null,
                pv30 = null,
                pv70 = null,
                pv100 = null,
                vol = 3,
                oi = 4
            ),
            Netflow2Row(
                date = LocalDate.of(2021, 1, 2),
                ticker = "SBER",
                p30 = null,
                p70 = null,
                p100 = null,
                pv30 = null,
                pv70 = null,
                pv100 = null,
                vol = null,
                oi = 5
            )
        )

        coEvery { client.fetchWindow("SBER", windows[0]) } returns Result.success(firstRows)
        coEvery { client.fetchWindow("SBER", windows[1]) } returns Result.success(secondRows)
        coJustRun { repository.upsert(any()) }

        val result = loader.upsert(" sber ", from, till)

        coVerify(exactly = 2) { repository.upsert(any()) }
        assertEquals(2, result.windows)
        assertEquals(3, result.rowsFetched)
        assertEquals(3, result.rowsUpserted)
        assertEquals(LocalDate.of(2021, 1, 2), result.maxDate)
        assertEquals("SBER", result.sec)
    }

    @Test
    fun `records metrics for successful load`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = Netflow2Metrics(registry)
        val client = io.mockk.mockk<Netflow2Client>()
        val repository = io.mockk.mockk<Netflow2Repository>()
        val loader = Netflow2Loader(client, repository, metrics)

        val from = LocalDate.of(2024, 1, 1)
        val till = LocalDate.of(2024, 1, 3)
        val window = Netflow2PullWindow.ofInclusive(from, till)
        val rows = listOf(
            Netflow2Row(LocalDate.of(2024, 1, 2), "SBER", 1, null, null, null, null, null, null, null)
        )

        coEvery { client.fetchWindow("SBER", window) } returns Result.success(rows)
        coJustRun { repository.upsert(any()) }

        val result = loader.upsert("SBER", from, till)

        assertEquals(LocalDate.of(2024, 1, 2), result.maxDate)

        val gauge = registry.find("feed_last_ts").tags("src", "netflow2").gauge()
        assertNotNull(gauge)
        val expectedEpoch = result.maxDate!!.atStartOfDay(ZoneOffset.UTC).toEpochSecond().toDouble()
        assertEquals(expectedEpoch, gauge.value())

        val timer = registry.find("feed_pull_latency_seconds").tags("src", "netflow2").timer()
        assertNotNull(timer)
        assertTrue(timer.count() > 0)

        assertEquals(1.0, metrics.pullSuccess.count())
        assertEquals(0.0, metrics.pullError.count())
    }

    @Test
    fun `rejects ticker with whitespace`() = runTest {
        val metrics = Netflow2Metrics(SimpleMeterRegistry())
        val client = io.mockk.mockk<Netflow2Client>()
        val repository = io.mockk.mockk<Netflow2Repository>()
        val loader = Netflow2Loader(client, repository, metrics)

        val ex = assertFailsWith<Netflow2LoaderError.ValidationError> {
            loader.upsert("S BER", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
        }

        assertTrue(ex.message?.startsWith("sec:") == true)
    }

    @Test
    fun `rejects ticker with unsupported characters`() = runTest {
        val metrics = Netflow2Metrics(SimpleMeterRegistry())
        val client = io.mockk.mockk<Netflow2Client>()
        val repository = io.mockk.mockk<Netflow2Repository>()
        val loader = Netflow2Loader(client, repository, metrics)

        val ex = assertFailsWith<Netflow2LoaderError.ValidationError> {
            loader.upsert("SBER?x=1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
        }

        assertTrue(ex.message?.startsWith("sec:") == true)
    }

    @Test
    fun `propagates assertion error from client`() = runTest {
        val metrics = Netflow2Metrics(SimpleMeterRegistry())
        val client = io.mockk.mockk<Netflow2Client>()
        val repository = io.mockk.mockk<Netflow2Repository>()
        val loader = Netflow2Loader(client, repository, metrics)

        val from = LocalDate.of(2024, 1, 1)
        val till = LocalDate.of(2024, 1, 1)
        val window = Netflow2PullWindow.ofInclusive(from, till)

        coEvery { client.fetchWindow("SBER", window) } throws AssertionError("boom")

        assertFailsWith<AssertionError> {
            loader.upsert("SBER", from, till)
        }
    }
}
