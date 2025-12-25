package repo

import db.DatabaseFactory.dbQuery
import java.sql.Date
import java.sql.Types
import java.util.Locale
import netflow2.Netflow2Row
import org.jetbrains.exposed.sql.transactions.TransactionManager
import repo.tables.MoexNetflow2Table

interface Netflow2Repository {
    suspend fun upsert(rows: List<Netflow2Row>)
}

class PostgresNetflow2Repository : Netflow2Repository {
    override suspend fun upsert(rows: List<Netflow2Row>) {
        if (rows.isEmpty()) return

        dbQuery {
            val jdbcConnection = TransactionManager.current().connection.connection as java.sql.Connection
            jdbcConnection.prepareStatement(UPSERT_SQL).use { statement ->
                rows.forEach { row ->
                    val ticker = row.ticker.trim().uppercase(Locale.ROOT)
                    require(ticker.isNotBlank()) { "ticker must not be blank" }
                    statement.setDate(1, Date.valueOf(row.date))
                    statement.setString(2, ticker)
                    statement.bindLong(3, row.p30)
                    statement.bindLong(4, row.p70)
                    statement.bindLong(5, row.p100)
                    statement.bindLong(6, row.pv30)
                    statement.bindLong(7, row.pv70)
                    statement.bindLong(8, row.pv100)
                    statement.bindLong(9, row.vol)
                    statement.bindLong(10, row.oi)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun java.sql.PreparedStatement.bindLong(index: Int, value: Long?) {
        if (value == null) {
            setNull(index, Types.BIGINT)
        } else {
            setLong(index, value)
        }
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO ${MoexNetflow2Table.tableName} (
                ${MoexNetflow2Table.date.name},
                ${MoexNetflow2Table.ticker.name},
                ${MoexNetflow2Table.p30.name},
                ${MoexNetflow2Table.p70.name},
                ${MoexNetflow2Table.p100.name},
                ${MoexNetflow2Table.pv30.name},
                ${MoexNetflow2Table.pv70.name},
                ${MoexNetflow2Table.pv100.name},
                ${MoexNetflow2Table.vol.name},
                ${MoexNetflow2Table.oi.name}
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (${MoexNetflow2Table.date.name}, ${MoexNetflow2Table.ticker.name}) DO UPDATE
            SET
                ${MoexNetflow2Table.p30.name} = EXCLUDED.${MoexNetflow2Table.p30.name},
                ${MoexNetflow2Table.p70.name} = EXCLUDED.${MoexNetflow2Table.p70.name},
                ${MoexNetflow2Table.p100.name} = EXCLUDED.${MoexNetflow2Table.p100.name},
                ${MoexNetflow2Table.pv30.name} = EXCLUDED.${MoexNetflow2Table.pv30.name},
                ${MoexNetflow2Table.pv70.name} = EXCLUDED.${MoexNetflow2Table.pv70.name},
                ${MoexNetflow2Table.pv100.name} = EXCLUDED.${MoexNetflow2Table.pv100.name},
                ${MoexNetflow2Table.vol.name} = EXCLUDED.${MoexNetflow2Table.vol.name},
                ${MoexNetflow2Table.oi.name} = EXCLUDED.${MoexNetflow2Table.oi.name}
        """.trimIndent()
    }
}
