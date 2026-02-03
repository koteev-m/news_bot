package db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.TransactionManager

object DatabaseFactory {
    private lateinit var dataSource: HikariDataSource

    @Synchronized
    fun init(config: HikariConfig = HikariConfigFactory.create()) {
        if (::dataSource.isInitialized) {
            return
        }
        dataSource = HikariDataSource(config)
        Database.connect(dataSource)
    }

    suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, statement = block)

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    fun isInitialized(): Boolean = ::dataSource.isInitialized

    fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        check(::dataSource.isInitialized) { "DatabaseFactory not initialized" }
        return dataSource.connection.use(block)
    }

    suspend fun ping() {
        dbQuery { TransactionManager.current().exec("select 1") { } }
    }
}
