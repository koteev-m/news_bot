package it

import data.moex.Netflow2Client
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import netflow2.Netflow2Loader
import netflow2.Netflow2PullWindow
import netflow2.Netflow2Row
import observability.feed.Netflow2Metrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import repo.PostgresNetflow2Repository
import repo.tables.MoexNetflow2Table

@Tag("integration")
class Netflow2LoaderIT {
    @Test
    fun `loads two windows into postgres idempotently`() = runBlocking {
        TestDb.withMigratedDatabase { _ ->
            val client = io.mockk.mockk<Netflow2Client>()
            val repository = PostgresNetflow2Repository()
            val feedMetrics = Netflow2Metrics(SimpleMeterRegistry())
            val loader = Netflow2Loader(client, repository, feedMetrics)

            val from = LocalDate.of(2018, 1, 1)
            val till = LocalDate.of(2021, 1, 2)
            val windows = Netflow2PullWindow.splitInclusive(from, till)

            val firstWindowRows = listOf(
                Netflow2Row(LocalDate.of(2018, 1, 1), "SBER", 1, null, null, null, null, null, 10, 11),
                Netflow2Row(LocalDate.of(2020, 12, 31), "SBER", 2, null, null, null, null, null, null, null)
            )
            val updatedFirstWindowRows = listOf(
                Netflow2Row(LocalDate.of(2018, 1, 1), "SBER", 5, null, null, null, null, null, 10, 11),
                Netflow2Row(LocalDate.of(2020, 12, 31), "SBER", 2, null, null, null, null, null, null, null)
            )
            val secondWindowRows = listOf(
                Netflow2Row(LocalDate.of(2021, 1, 1), "SBER", null, null, null, null, null, null, null, 20),
                Netflow2Row(LocalDate.of(2021, 1, 2), "SBER", null, null, null, null, null, null, null, 21)
            )

            io.mockk.coEvery { client.fetchWindow("SBER", windows[0]) } returnsMany listOf(
                Result.success(firstWindowRows),
                Result.success(updatedFirstWindowRows)
            )
            io.mockk.coEvery { client.fetchWindow("SBER", windows[1]) } returnsMany listOf(
                Result.success(secondWindowRows),
                Result.success(secondWindowRows)
            )

            val first = loader.upsert("sber", from, till)
            val countAfterFirst = TestDb.tx { MoexNetflow2Table.selectAll().count() }

            val second = loader.upsert("sber", from, till)
            val countAfterSecond = TestDb.tx { MoexNetflow2Table.selectAll().count() }
            val updated = TestDb.tx {
                MoexNetflow2Table
                    .select { (MoexNetflow2Table.ticker eq "SBER") and (MoexNetflow2Table.date eq LocalDate.of(2018, 1, 1)) }
                    .single()[MoexNetflow2Table.p30]
            }

            assertEquals(4, first.rowsUpserted)
            assertEquals(4, first.rowsFetched)
            assertEquals(4L, countAfterFirst)
            assertEquals(4L, countAfterSecond)
            assertEquals(first.rowsUpserted, second.rowsUpserted)
            assertEquals(first.maxDate, second.maxDate)
            assertEquals(5L, updated)
        }
    }
}
