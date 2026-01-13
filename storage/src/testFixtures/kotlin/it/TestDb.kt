package it

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import repo.tables.UsersTable

object TestDb {
    suspend fun <T> withMigratedDatabase(block: suspend (HikariDataSource) -> T): T {
        TestPostgres.assumeDockerAvailable()
        val dataSource = TestPostgres.dataSource()
        return try {
            resetDatabase(dataSource)
            TestPostgres.connectExposed(dataSource)
            block(dataSource)
        } finally {
            withContext(Dispatchers.IO) {
                dataSource.close()
            }
        }
    }

    suspend fun <T> tx(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, statement = block)

    suspend fun createUser(telegramUserId: Long? = null): Long =
        tx {
            UsersTable.insert {
                it[UsersTable.tgUserId] = telegramUserId
            } get UsersTable.userId
        }

    private fun resetDatabase(dataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
        runCatching { flyway.clean() }
        TestPostgres.migrate(dataSource)
    }
}
