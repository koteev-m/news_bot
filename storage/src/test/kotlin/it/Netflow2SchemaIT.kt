package it

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.transactions.TransactionManager

@Tag("integration")
class Netflow2SchemaIT {
    @Test
    fun `moex_netflow2 has ticker_not_blank constraint`() = runBlocking {
        TestDb.withMigratedDatabase {
            val constraintDef = TestDb.tx {
                exec(
                    """
                    select lower(pg_get_constraintdef(c.oid))
                    from pg_constraint c
                    join pg_class t on t.oid = c.conrelid
                    join pg_namespace n on n.oid = t.relnamespace
                    where n.nspname = current_schema()
                      and t.relname = 'moex_netflow2'
                      and c.conname = 'moex_netflow2_ticker_not_blank'
                    """.trimIndent(),
                    transform = { rs -> if (rs.next()) rs.getString(1) else null }
                )
            }

            assertNotNull(constraintDef)
            val trimsTicker = Regex("btrim\\s*\\(\\s*ticker").containsMatchIn(constraintDef!!)
            val checksEmpty = Regex("<>\\s*''(::text)?").containsMatchIn(constraintDef)
            assertTrue(
                trimsTicker && checksEmpty,
                "constraint definition should trim ticker and compare to empty string"
            )
        }
    }

    @Test
    fun `moex_netflow2 rejects blank tickers`() = runBlocking {
        TestDb.withMigratedDatabase { _ ->
            val errorsByTicker = listOf("", "   ").associateWith { ticker ->
                runCatching {
                    TestDb.tx {
                        val connection = TransactionManager.current().connection.connection as java.sql.Connection
                        connection.prepareStatement(
                            "insert into moex_netflow2 (date, ticker) values (date '2024-01-01', ?)"
                        ).use { stmt ->
                            stmt.setString(1, ticker)
                            stmt.executeUpdate()
                        }
                    }
                }.exceptionOrNull()
            }

            errorsByTicker.forEach { (ticker, error) ->
                assertNotNull(error, "ticker '$ticker' should be rejected")
                val sqlState = when (error) {
                    is ExposedSQLException -> error.sqlState
                    is java.sql.SQLException -> error.sqlState
                    else -> (error?.cause as? java.sql.SQLException)?.sqlState
                }
                assertTrue(
                    sqlState == "23514" ||
                        error?.message?.contains("moex_netflow2_ticker_not_blank") == true,
                    "expected check violation for ticker '$ticker'"
                )
            }

            val count = TestDb.tx {
                exec("select count(*) from moex_netflow2") { rs -> if (rs.next()) rs.getInt(1) else null }
            } ?: -1
            assertTrue(count == 0, "moex_netflow2 should remain empty after rejected inserts")
        }
    }
}
