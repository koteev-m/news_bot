package portfolio

import kotlinx.coroutines.runBlocking
import model.FxRate
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FxRateServiceTest {
    private val zoneId: ZoneId = ZoneOffset.UTC

    @Test
    fun `returns rate for exact date`() =
        runBlocking {
            val repository =
                FakeFxRateRepository(
                    mapOf(
                        "USD" to listOf(rate("USD", LocalDate.of(2024, 1, 10), BigDecimal("90.1234"))),
                    ),
                )
            val service = FxRateService(repository, zoneId)

            val result = service.rateOn(LocalDate.of(2024, 1, 10), "usd")

            assertTrue(result.isSuccess)
            assertEquals(BigDecimal("90.1234"), result.getOrNull())
            assertEquals(1, repository.calls)
        }

    @Test
    fun `falls back to previous available date`() =
        runBlocking {
            val repository =
                FakeFxRateRepository(
                    mapOf(
                        "USD" to
                            listOf(
                                rate("USD", LocalDate.of(2024, 1, 5), BigDecimal("92.50")),
                            ),
                    ),
                )
            val service = FxRateService(repository, zoneId)

            val result = service.rateOn(LocalDate.of(2024, 1, 7), "USD")
            assertTrue(result.isSuccess)
            assertEquals(normalize(BigDecimal("92.50")), result.getOrNull())
            assertEquals(1, repository.calls)

            val cached = service.rateOn(LocalDate.of(2024, 1, 6), "USD")
            assertTrue(cached.isSuccess)
            assertEquals(normalize(BigDecimal("92.50")), cached.getOrNull())
            assertEquals(1, repository.calls)
        }

    @Test
    fun `calculates cross rate using base currency`() =
        runBlocking {
            val repository =
                FakeFxRateRepository(
                    mapOf(
                        "EUR" to listOf(rate("EUR", LocalDate.of(2024, 1, 10), BigDecimal("100"))),
                        "USD" to listOf(rate("USD", LocalDate.of(2024, 1, 10), BigDecimal("90"))),
                    ),
                )
            val service = FxRateService(repository, zoneId)

            val result = service.rateOn(LocalDate.of(2024, 1, 10), "EUR", base = "USD")

            val expected = normalize(BigDecimal("100").divide(BigDecimal("90"), java.math.MathContext.DECIMAL128))

            assertTrue(result.isSuccess)
            assertEquals(expected, result.getOrNull())
            assertEquals(2, repository.calls)
        }

    @Test
    fun `rejects unknown currency codes`() =
        runBlocking {
            val service = FxRateService(FakeFxRateRepository(emptyMap()), zoneId)

            val result = service.rateOn(LocalDate.of(2024, 1, 10), "US1")

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertIs<PortfolioException>(exception)
            assertIs<PortfolioError.Validation>(exception.error)
        }

    @Test
    fun `returns not found when data missing`() =
        runBlocking {
            val repository =
                FakeFxRateRepository(
                    mapOf(
                        "USD" to listOf(rate("USD", LocalDate.of(2024, 1, 5), BigDecimal("92.50"))),
                    ),
                )
            val service = FxRateService(repository, zoneId)

            val result = service.rateOn(LocalDate.of(2024, 1, 4), "EUR")

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertIs<PortfolioException>(exception)
            assertIs<PortfolioError.NotFound>(exception.error)
        }

    private fun rate(
        ccy: String,
        date: LocalDate,
        value: BigDecimal,
    ): FxRate =
        FxRate(
            ccy = ccy,
            ts = date.atTime(LocalTime.NOON).atZone(zoneId).toInstant(),
            rateRub = value,
            source = "test",
        )

    private class FakeFxRateRepository(
        entries: Map<String, List<FxRate>>,
    ) : FxRateRepository {
        private val rates: Map<String, List<FxRate>> =
            entries.mapValues { (_, list) ->
                list.sortedBy { it.ts }
            }
        var calls: Int = 0

        override suspend fun findOnOrBefore(
            ccy: String,
            timestamp: Instant,
        ): FxRate? {
            calls += 1
            return rates[ccy]?.filter { it.ts <= timestamp }?.maxByOrNull { it.ts }
        }
    }

    private fun normalize(value: BigDecimal): BigDecimal {
        val stripped = value.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }
}
