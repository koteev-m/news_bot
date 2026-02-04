package routes

import io.ktor.http.HttpStatusCode
import java.sql.SQLException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import routes.dto.CreatePortfolioRequest
import routes.dto.PortfolioItemResponse
import security.JwtConfig
import security.JwtSupport

class PortfolioRoutesTest {
    private val jwtConfig = JwtConfig(
        issuer = "newsbot",
        audience = "newsbot-clients",
        realm = "newsbot-api",
        secret = "test-secret",
        accessTtlMinutes = 60,
    )

    @Test
    fun `GET portfolio returns portfolios for authenticated user`() = runBlocking {
        val deps = FakeDeps()
        deps.records += listOf(
            PortfolioRecord(
                id = "portfolio-1",
                name = "Main",
                baseCurrency = "USD",
                valuationMethod = "AVERAGE",
                isActive = true,
                createdAt = Instant.parse("2024-03-10T10:00:00Z"),
            ),
            PortfolioRecord(
                id = "portfolio-2",
                name = "Crypto",
                baseCurrency = "USDT",
                valuationMethod = "FIFO",
                isActive = false,
                createdAt = Instant.parse("2024-03-11T09:15:00Z"),
            ),
        )

        val subject = subjectFromToken(issueToken("123456"))
        val result = processPortfolioList(subject, deps.toDeps())

        assertEquals(HttpStatusCode.OK, result.status)
        val payload = result.payload as List<PortfolioItemResponse>
        assertEquals(2, payload.size)
        assertEquals("Main", payload[0].name)
        assertEquals("Crypto", payload[1].name)
    }

    @Test
    fun `POST portfolio creates portfolio`() = runBlocking {
        val deps = FakeDeps()
        val subject = subjectFromToken(issueToken("999"))
        val request = CreatePortfolioRequest("Growth", "rub")

        val result = processPortfolioCreate(subject, request, deps.toDeps())

        assertEquals(HttpStatusCode.Created, result.status)
        val payload = result.payload as PortfolioItemResponse
        assertEquals("Growth", payload.name)
        assertEquals("RUB", payload.baseCurrency)
        assertEquals("AVERAGE", payload.valuationMethod)
        assertTrue(deps.records.any { it.name == "Growth" })
    }

    @Test
    fun `POST portfolio normalizes base currency`() = runBlocking {
        val deps = FakeDeps().apply { defaultMethod = "FIFO" }
        val subject = subjectFromToken(issueToken("555"))
        val request = CreatePortfolioRequest("Alpha", "rub")

        val result = processPortfolioCreate(subject, request, deps.toDeps())

        assertEquals(HttpStatusCode.Created, result.status)
        val payload = result.payload as PortfolioItemResponse
        assertEquals("RUB", payload.baseCurrency)
        assertEquals("FIFO", payload.valuationMethod)
        assertEquals("RUB", deps.records.last().baseCurrency)
    }

    @Test
    fun `POST portfolio with short name returns 400`() = runBlocking {
        val deps = FakeDeps()
        val subject = subjectFromToken(issueToken("42"))
        val request = CreatePortfolioRequest("A", "USD")

        val result = processPortfolioCreate(subject, request, deps.toDeps())

        assertEquals(HttpStatusCode.BadRequest, result.status)
        val payload = result.payload
        assertIs<ApiErrorResponse>(payload)
        assertTrue(payload.details.any { it.field == "name" })
    }

    @Test
    fun `POST portfolio with duplicate name returns 409`() = runBlocking {
        val deps = FakeDeps().apply { createException = SQLException("duplicate", UNIQUE_VIOLATION) }
        val subject = subjectFromToken(issueToken("77"))
        val request = CreatePortfolioRequest("Dup", "USD")

        val result = processPortfolioCreate(subject, request, deps.toDeps())

        assertEquals(HttpStatusCode.Conflict, result.status)
        val payload = result.payload
        assertIs<ApiErrorResponse>(payload)
        assertEquals("conflict", payload.error)
    }

    @Test
    fun `requests without JWT return 401`() = runBlocking {
        val deps = FakeDeps()

        val getResult = processPortfolioList(null, deps.toDeps())
        assertEquals(HttpStatusCode.Unauthorized, getResult.status)

        val postResult = processPortfolioCreate(null, CreatePortfolioRequest("X", "USD"), deps.toDeps())
        assertEquals(HttpStatusCode.Unauthorized, postResult.status)
    }

    private fun issueToken(subject: String): String = JwtSupport.issueToken(jwtConfig, subject)

    private fun subjectFromToken(token: String): String = JwtSupport.verify(jwtConfig).verify(token).subject

    private class FakeDeps {
        var defaultMethod: String = "AVERAGE"
        var createException: Throwable? = null
        val records = mutableListOf<PortfolioRecord>()
        private var counter = 0

        fun toDeps(): PortfolioRouteDeps = PortfolioRouteDeps(
            defaultValuationMethod = defaultMethod,
            resolveUser = { 1L },
            listPortfolios = { records.toList() },
            createPortfolio = { _, valid ->
                createException?.let { throw it }
                counter += 1
                val record = PortfolioRecord(
                    id = "portfolio-$counter",
                    name = valid.name,
                    baseCurrency = valid.baseCurrency,
                    valuationMethod = valid.valuationMethod,
                    isActive = true,
                    createdAt = Instant.parse("2024-03-10T00:00:00Z").plusSeconds(counter.toLong()),
                )
                records += record
                record
            },
        )
    }

    companion object {
        private const val UNIQUE_VIOLATION = "23505"
    }
}
