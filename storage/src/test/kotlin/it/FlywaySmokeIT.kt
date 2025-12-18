package it

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class FlywaySmokeIT {
    @Test
    fun `migrations apply and base tables exist`() = runBlocking {
        TestDb.withMigratedDatabase { _ ->
            val ping = TestDb.tx {
                exec("select 1") { rs ->
                    if (rs.next()) rs.getInt(1) else null
                }
            }
            assertEquals(1, ping)

            val tables = TestDb.tx {
                exec(
                    """
                    select table_name
                    from information_schema.tables
                    where table_schema = 'public'
                      and table_name in ('users', 'portfolios', 'trades')
                    """.trimIndent()
                ) { rs ->
                    val names = mutableSetOf<String>()
                    while (rs.next()) {
                        names += rs.getString(1)
                    }
                    names
                } ?: emptySet()
            }
            assertTrue(tables.containsAll(setOf("users", "portfolios", "trades")))
        }
    }
}
