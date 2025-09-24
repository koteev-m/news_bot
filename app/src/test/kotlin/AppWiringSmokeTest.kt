package app

import di.installTestServices
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import routes.PortfolioRecord
import routes.PortfolioRouteDeps
import routes.PortfolioRouteDepsKey
import routes.dto.MoneyDto
import routes.dto.PortfolioItemResponse
import security.JwtConfig
import security.JwtSupport

class AppWiringSmokeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jwtConfig = JwtConfig(
        issuer = "newsbot-test",
        audience = "newsbot-clients-test",
        realm = "newsbot-api-test",
        secret = "test_secret_change_me",
        accessTtlMinutes = 5,
    )

    @Test
    fun `public endpoints respond as expected`() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        application {
            this@AppWiringSmokeTest.configureFakeDatabase()
            module()
            installTestServices()
            installPortfolioRouteDeps()
        }

        val healthResponse = client.get("/health/db")
        assertEquals(HttpStatusCode.OK, healthResponse.status)
        assertTrue(healthResponse.bodyAsText().contains("\"db\""))

        val quotesResponse = client.get("/api/quotes/closeOrLast?instrumentId=1&date=2025-09-20")
        assertEquals(HttpStatusCode.OK, quotesResponse.status)
        val quotePayload = json.decodeFromString<MoneyDto>(quotesResponse.bodyAsText())
        assertEquals("123.45000000", quotePayload.amount)
        assertEquals("RUB", quotePayload.ccy)

        val authResponse = client.post("/api/auth/telegram/verify")
        assertTrue(authResponse.status.value < 500)
    }

    @Test
    fun `protected endpoints enforce jwt`() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        application {
            this@AppWiringSmokeTest.configureFakeDatabase()
            module()
            installTestServices()
            installPortfolioRouteDeps()
        }

        val unauthorized = client.get("/api/portfolio")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val token = JwtSupport.issueToken(jwtConfig, subject = "7446417641")
        val authorized = client.get("/api/portfolio") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
        val payload = json.decodeFromString<List<PortfolioItemResponse>>(authorized.bodyAsText())
        assertEquals(1, payload.size)
        assertEquals("Demo Portfolio", payload.first().name)
    }

    private fun Application.installPortfolioRouteDeps() {
        val record = PortfolioRecord(
            id = "portfolio-demo",
            name = "Demo Portfolio",
            baseCurrency = "RUB",
            valuationMethod = "AVERAGE",
            isActive = true,
            createdAt = java.time.Instant.parse("2024-01-01T00:00:00Z"),
        )
        val deps = PortfolioRouteDeps(
            defaultValuationMethod = "AVERAGE",
            resolveUser = { it },
            listPortfolios = { listOf(record) },
            createPortfolio = { _, _ -> record },
        )
        attributes.put(PortfolioRouteDepsKey, deps)
    }

    private fun configureFakeDatabase() {
        if (FAKE_DB_INITIALIZED.compareAndSet(false, true)) {
            Database.connect(FakeDataSource)
        }
    }

    private object FakeDataSource : DataSource {
        override fun getConnection(): Connection = FakeConnection().proxy

        override fun getConnection(username: String?, password: String?): Connection = getConnection()

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) {}

        override fun setLoginTimeout(seconds: Int) {}

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = Logger.getLogger("FakeDataSource")

        override fun <T> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()

        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    private class FakeConnection : InvocationHandler {
        val proxy: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            this,
        ) as Connection

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "createStatement" -> FakeStatement(this.proxy).proxy
            "prepareStatement" -> FakePreparedStatement(this.proxy).proxy
            "setAutoCommit", "commit", "rollback", "close", "setReadOnly", "setCatalog",
            "setTransactionIsolation", "clearWarnings", "setHoldability", "setClientInfo",
            "setSchema", "abort", "setNetworkTimeout" -> null
            "getAutoCommit" -> true
            "isClosed" -> false
            "isValid" -> true
            "getWarnings" -> null
            "getMetaData" -> FakeDatabaseMetaData.proxy
            "getCatalog" -> null
            "getClientInfo" -> null
            "getHoldability" -> ResultSet.HOLD_CURSORS_OVER_COMMIT
            "getSchema" -> null
            "getNetworkTimeout" -> 0
            "unwrap" -> throw SQLFeatureNotSupportedException()
            "isWrapperFor" -> false
            "nativeSQL" -> args?.getOrNull(0)
            else -> defaultReturn(method.returnType)
        }
    }

    private class FakeStatement(private val connection: Connection) : InvocationHandler {
        val proxy: Statement = Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
            this,
        ) as Statement

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "close", "setFetchDirection", "setFetchSize", "setMaxFieldSize", "setMaxRows",
            "setPoolable", "setQueryTimeout", "clearBatch", "clearWarnings", "cancel" -> null
            "execute", "executeLargeBatch", "executeLargeUpdate" -> false
            "executeUpdate", "executeLargeUpdate" -> 0
            "executeBatch" -> intArrayOf()
            "getResultSet" -> null
            "getUpdateCount" -> 0
            "getMoreResults" -> false
            "getGeneratedKeys" -> FakeResultSet.proxy
            "getConnection" -> connection
            else -> defaultReturn(method.returnType)
        }
    }

    private class FakePreparedStatement(private val connection: Connection) : InvocationHandler {
        val proxy: PreparedStatement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java, Statement::class.java),
            this,
        ) as PreparedStatement

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "executeQuery" -> FakeResultSet.proxy
            "execute", "executeUpdate", "executeLargeUpdate" -> false
            "getResultSet" -> null
            "getUpdateCount" -> 0
            "getMoreResults" -> false
            "getGeneratedKeys" -> FakeResultSet.proxy
            "clearParameters", "close", "addBatch", "setFetchSize", "setFetchDirection",
            "setMaxFieldSize", "setMaxRows", "setQueryTimeout", "setEscapeProcessing" -> null
            "getConnection" -> connection
            else -> defaultReturn(method.returnType)
        }
    }

    private object FakeResultSet : InvocationHandler {
        val proxy: ResultSet = Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            this,
        ) as ResultSet

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "next" -> false
            "close" -> null
            "wasNull" -> false
            else -> defaultReturn(method.returnType)
        }
    }

    private object FakeDatabaseMetaData : InvocationHandler {
        val proxy: DatabaseMetaData = Proxy.newProxyInstance(
            DatabaseMetaData::class.java.classLoader,
            arrayOf(DatabaseMetaData::class.java),
            this,
        ) as DatabaseMetaData

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getUserName" -> "test"
            "getDatabaseProductName" -> "PostgreSQL"
            "getDatabaseProductVersion" -> "15.0"
            "getDriverName" -> "PostgreSQL JDBC Driver"
            "getDriverVersion" -> "42.7.4"
            "getURL" -> "jdbc:postgresql://localhost/test"
            "getDefaultTransactionIsolation" -> Connection.TRANSACTION_NONE
            "supportsTransactions" -> false
            "getIdentifierQuoteString" -> "\""
            "getSQLKeywords" -> ""
            "getNumericFunctions" -> ""
            "getStringFunctions" -> ""
            "getSystemFunctions" -> ""
            "getTimeDateFunctions" -> ""
            "getCatalogSeparator" -> "."
            "getCatalogTerm" -> "catalog"
            "getSchemaTerm" -> "schema"
            "getProcedureTerm" -> "procedure"
            "nullsAreSortedHigh", "nullsAreSortedLow", "nullsAreSortedAtStart", "nullsAreSortedAtEnd" -> false
            "supportsMixedCaseIdentifiers", "storesUpperCaseIdentifiers", "storesLowerCaseIdentifiers",
            "storesMixedCaseIdentifiers", "supportsMixedCaseQuotedIdentifiers", "storesUpperCaseQuotedIdentifiers",
            "storesLowerCaseQuotedIdentifiers", "storesMixedCaseQuotedIdentifiers" -> false
            "supportsANSI92EntryLevelSQL", "supportsANSI92IntermediateSQL", "supportsANSI92FullSQL" -> false
            "supportsAlterTableWithAddColumn", "supportsAlterTableWithDropColumn", "supportsColumnAliasing" -> false
            "supportsGroupBy", "supportsGroupByUnrelated", "supportsGroupByBeyondSelect" -> false
            "supportsLikeEscapeClause", "supportsMultipleResultSets", "supportsMultipleTransactions",
            "supportsDifferentTableCorrelationNames", "supportsExpressionsInOrderBy", "supportsOrderByUnrelated",
            "supportsOuterJoins", "supportsFullOuterJoins", "supportsLimitedOuterJoins" -> false
            "getExtraNameCharacters" -> ""
            "usesLocalFiles", "usesLocalFilePerTable", "supportsOpenCursorsAcrossCommit",
            "supportsOpenCursorsAcrossRollback", "supportsOpenStatementsAcrossCommit",
            "supportsOpenStatementsAcrossRollback" -> false
            "getMaxBinaryLiteralLength", "getMaxCharLiteralLength", "getMaxColumnNameLength", "getMaxColumnsInGroupBy",
            "getMaxColumnsInIndex", "getMaxColumnsInOrderBy", "getMaxColumnsInSelect", "getMaxColumnsInTable",
            "getMaxConnections", "getMaxCursorNameLength", "getMaxIndexLength", "getMaxSchemaNameLength",
            "getMaxProcedureNameLength", "getMaxCatalogNameLength", "getMaxRowSize", "getMaxStatementLength",
            "getMaxStatements", "getMaxTableNameLength", "getMaxTablesInSelect", "getMaxUserNameLength" -> 0
            "supportsResultSetType", "supportsResultSetConcurrency", "supportsBatchUpdates",
            "supportsSavepoints", "supportsNamedParameters", "supportsMultipleOpenResults",
            "supportsGetGeneratedKeys", "supportsResultSetHoldability" -> false
            "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT
            "getRowIdLifetime" -> java.sql.RowIdLifetime.ROWID_UNSUPPORTED
            "unwrap" -> throw SQLFeatureNotSupportedException()
            "isWrapperFor" -> false
            else -> defaultReturn(method.returnType)
        }
    }

    companion object {
        private val FAKE_DB_INITIALIZED = AtomicBoolean(false)
    }
}

private fun defaultReturn(returnType: Class<*>): Any? = when (returnType) {
    java.lang.Boolean.TYPE -> false
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    java.lang.Void.TYPE -> null
    else -> null
}
