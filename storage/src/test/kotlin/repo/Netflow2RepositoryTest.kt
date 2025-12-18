package repo

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import netflow2.Netflow2Row
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import repo.tables.MoexNetflow2Table
import it.TestDb

@Tag("integration")
class Netflow2RepositoryTest {
    @Test
    fun `upsert is idempotent`() = runBlocking {
        TestDb.withMigratedDatabase { _ ->
            val repository: Netflow2Repository = PostgresNetflow2Repository()
            val date = LocalDate.of(2024, 1, 1)

            val initial = Netflow2Row(
                date = date,
                ticker = " sber ",
                p30 = 1L,
                p70 = 2L,
                p100 = 3L,
                pv30 = 4L,
                pv70 = 5L,
                pv100 = 6L,
                vol = null,
                oi = 8L,
            )

            val updated = initial.copy(p70 = 20L, pv100 = null, vol = 70L)

            repository.upsert(listOf(initial))
            repository.upsert(listOf(updated))
            repository.upsert(listOf(updated))

            transaction {
                val rows = MoexNetflow2Table.selectAll().toList()
                assertEquals(1, rows.size)

                val stored = rows.single()
                assertEquals(date, stored[MoexNetflow2Table.date])
                assertEquals("SBER", stored[MoexNetflow2Table.ticker])
                assertEquals(1L, stored[MoexNetflow2Table.p30])
                assertEquals(20L, stored[MoexNetflow2Table.p70])
                assertEquals(3L, stored[MoexNetflow2Table.p100])
                assertEquals(4L, stored[MoexNetflow2Table.pv30])
                assertEquals(5L, stored[MoexNetflow2Table.pv70])
                assertEquals(null, stored[MoexNetflow2Table.pv100])
                assertEquals(70L, stored[MoexNetflow2Table.vol])
                assertEquals(8L, stored[MoexNetflow2Table.oi])
            }
        }
    }
}
