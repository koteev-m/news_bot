package it

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import portfolio.errors.DomainResult
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import portfolio.model.Money
import portfolio.model.ValuationMethod
import portfolio.service.CsvImportService
import portfolio.service.PortfolioService
import portfolio.service.PositionCalc
import repo.InstrumentRepository
import repo.PortfolioRepository
import repo.PositionRepository
import repo.TradeRepository
import repo.mapper.toDbTimestamp
import repo.model.NewInstrument
import repo.model.NewPortfolio
import repo.tables.InstrumentsTable
import repo.tables.PositionsTable
import repo.tables.TradesTable

@Tag("integration")
class CsvImportIT {
    @Test
    fun `csv import reports inserted duplicates and failed rows`() = runBlocking {
        TestDb.withMigratedDatabase { _ ->
            val portfolioRepository = PortfolioRepository()
            val instrumentRepository = InstrumentRepository()
            val tradeRepository = TradeRepository()
            val positionRepository = PositionRepository()

            val clockInstant = Instant.parse("2024-03-01T00:00:00Z")
            val clock = Clock.fixed(clockInstant, ZoneOffset.UTC)

            val userId = TestDb.createUser(telegramUserId = 999_001L)
            val portfolio = portfolioRepository.create(
                NewPortfolio(
                    userId = userId,
                    name = "CSV",
                    baseCurrency = "RUB",
                    createdAt = clockInstant,
                ),
            )

            val instrument = instrumentRepository.createInstrument(
                NewInstrument(
                    clazz = "EQUITY",
                    exchange = "MOEX",
                    board = "TQBR",
                    symbol = "SBER",
                    isin = "RU0009029540",
                    cgId = null,
                    currency = "RUB",
                    createdAt = clockInstant,
                ),
            )

            val storage = RepositoryPortfolioStorage(
                instrumentRepository = instrumentRepository,
                fallbackCurrency = "RUB",
                clock = clock,
            )
            val portfolioService = PortfolioService(storage, clock)
            val csvImportService = CsvImportService(
                instrumentResolver = DatabaseInstrumentResolver(instrumentRepository),
                tradeLookup = DatabaseTradeLookup(tradeRepository),
                portfolioService = portfolioService,
            )

            val resource = checkNotNull(javaClass.classLoader.getResource("trades.csv"))
            Files.newBufferedReader(Paths.get(resource.toURI())).use { reader ->
                val report = csvImportService.import(
                    portfolioId = portfolio.portfolioId,
                    reader = reader,
                    valuationMethod = ValuationMethod.AVERAGE,
                ).getOrThrow()

                assertEquals(3, report.inserted)
                assertEquals(1, report.skippedDuplicates)
                assertEquals(1, report.failed.size)
                val failure = report.failed.single()
                assertEquals(6, failure.lineNumber)
                assertNull(failure.extId)
                assertTrue(failure.message.contains("Cannot sell more than current quantity"))
            }

            val trades = tradeRepository.listByPortfolio(portfolio.portfolioId, limit = 10)
            assertEquals(3, trades.size)
            assertEquals(setOf("ext-1", "ext-2"), trades.mapNotNull { it.extId }.toSet())

            val position = positionRepository.find(portfolio.portfolioId, instrument.instrumentId)
            assertNotNull(position)
            checkNotNull(position)

            val calc = PositionCalc.AverageCostCalc()
            var expected = PositionCalc.Position.empty("RUB")
            expected = calc.applyBuy(expected, BigDecimal("10"), Money.of(BigDecimal("250"), "RUB"), Money.zero("RUB")).position
            expected = calc.applyBuy(expected, BigDecimal("5"), Money.of(BigDecimal("255"), "RUB"), Money.zero("RUB")).position
            expected = calc.applySell(expected, BigDecimal("4"), Money.of(BigDecimal("260"), "RUB"), Money.zero("RUB")).position

            assertEquals(expected.quantity, position.qty)
            assertEquals(expected.averagePrice?.amount, position.avgPrice)
            assertEquals(expected.averagePrice?.currency, position.avgPriceCcy)
        }
    }
}

private class RepositoryPortfolioStorage(
    private val instrumentRepository: InstrumentRepository,
    private val fallbackCurrency: String,
    private val clock: Clock,
) : PortfolioService.Storage {
    override suspend fun <T> transaction(
        block: suspend PortfolioService.Storage.Transaction.() -> DomainResult<T>,
    ): DomainResult<T> = try {
        TestDb.tx {
            val tx = TransactionImpl(fallbackCurrency, clock)
            tx.block()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (ex: Throwable) {
        DomainResult.failure(ex)
    }

    override suspend fun listPositions(portfolioId: UUID): DomainResult<List<PortfolioService.PositionSummary>> = try {
        val summaries = TestDb.tx {
            PositionsTable
                .selectAll()
                .where { PositionsTable.portfolioId eq portfolioId }
                .map { row ->
                    val instrumentId = row[PositionsTable.instrumentId]
                    val instrument = instrumentRepository.findById(instrumentId)
                    val averageAmount = row[PositionsTable.avgPrice]
                    val averageCurrency = row[PositionsTable.avgPriceCcy]
                        ?: instrument?.currency
                        ?: fallbackCurrency
                    PortfolioService.PositionSummary(
                        portfolioId = row[PositionsTable.portfolioId],
                        instrumentId = instrumentId,
                        instrumentName = instrument?.symbol ?: "Instrument $instrumentId",
                        quantity = row[PositionsTable.qty],
                        averagePrice = averageAmount?.let { Money.of(it, averageCurrency) },
                        valuationMethod = ValuationMethod.AVERAGE,
                    )
                }
        }
        DomainResult.success(summaries)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (ex: Throwable) {
        DomainResult.failure(ex)
    }

    private inner class TransactionImpl(
        private val fallbackCurrency: String,
        private val clock: Clock,
    ) : PortfolioService.Storage.Transaction {
        override suspend fun loadPosition(
            portfolioId: UUID,
            instrumentId: Long,
            method: ValuationMethod,
        ): DomainResult<PortfolioService.StoredPosition?> = try {
            val result = PositionsTable
                .selectAll()
                .where { (PositionsTable.portfolioId eq portfolioId) and (PositionsTable.instrumentId eq instrumentId) }
                .limit(1)
                .singleOrNull()
                ?.let { row ->
                    val quantity = row[PositionsTable.qty]
                    val average = row[PositionsTable.avgPrice]
                    val currency = row[PositionsTable.avgPriceCcy]
                        ?: instrumentCurrency(instrumentId)
                        ?: fallbackCurrency
                    val costAmount = if (average != null) average.multiply(quantity) else BigDecimal.ZERO
                    val costBasis = Money.of(costAmount, currency)
                    val position = PositionCalc.Position(quantity, costBasis)
                    PortfolioService.StoredPosition(
                        portfolioId = portfolioId,
                        instrumentId = instrumentId,
                        valuationMethod = method,
                        position = position,
                        updatedAt = row[PositionsTable.updatedAt].toInstant(),
                    )
                }
            DomainResult.success(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            DomainResult.failure(ex)
        }

        override suspend fun savePosition(position: PortfolioService.StoredPosition): DomainResult<Unit> = try {
            val predicate = (PositionsTable.portfolioId eq position.portfolioId) and
                (PositionsTable.instrumentId eq position.instrumentId)
            val average = position.position.averagePrice
            val updated = PositionsTable.update({ predicate }) { statement ->
                statement[PositionsTable.qty] = position.position.quantity
                statement[PositionsTable.avgPrice] = average?.amount
                statement[PositionsTable.avgPriceCcy] = average?.currency
                statement[PositionsTable.updatedAt] = position.updatedAt.toUtcTimestamp()
            }
            if (updated == 0) {
                PositionsTable.insert { statement ->
                    statement[PositionsTable.portfolioId] = position.portfolioId
                    statement[PositionsTable.instrumentId] = position.instrumentId
                    statement[PositionsTable.qty] = position.position.quantity
                    statement[PositionsTable.avgPrice] = average?.amount
                    statement[PositionsTable.avgPriceCcy] = average?.currency
                    statement[PositionsTable.updatedAt] = position.updatedAt.toUtcTimestamp()
                }
            }
            DomainResult.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            DomainResult.failure(ex)
        }

        override suspend fun recordTrade(trade: PortfolioService.StoredTrade): DomainResult<Unit> = try {
            TradesTable.insert { statement ->
                statement[TradesTable.portfolioId] = trade.portfolioId
                statement[TradesTable.instrumentId] = trade.instrumentId
                statement[TradesTable.datetime] = trade.executedAt.toUtcTimestamp()
                statement[TradesTable.side] = trade.side.name
                statement[TradesTable.quantity] = trade.quantity
                statement[TradesTable.price] = trade.price.amount
                statement[TradesTable.priceCurrency] = trade.price.currency
                statement[TradesTable.fee] = trade.fee.amount
                statement[TradesTable.feeCurrency] = trade.fee.currency
                statement[TradesTable.tax] = trade.tax?.amount ?: BigDecimal.ZERO
                statement[TradesTable.taxCurrency] = trade.tax?.currency
                statement[TradesTable.broker] = trade.broker
                statement[TradesTable.note] = trade.note
                statement[TradesTable.extId] = trade.externalId
                statement[TradesTable.createdAt] = clock.instant().toUtcTimestamp()
            }
            DomainResult.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            DomainResult.failure(ex)
        }

        private fun instrumentCurrency(instrumentId: Long): String? =
            InstrumentsTable
                .selectAll()
                .where { InstrumentsTable.instrumentId eq instrumentId }
                .limit(1)
                .singleOrNull()
                ?.get(InstrumentsTable.currency)
    }
}

private class DatabaseInstrumentResolver(
    private val instrumentRepository: InstrumentRepository,
) : CsvImportService.InstrumentResolver {
    override suspend fun findBySymbol(
        exchange: String,
        board: String?,
        symbol: String,
    ): DomainResult<CsvImportService.InstrumentRef?> = try {
        val instrument = instrumentRepository.findBySymbol(exchange, board, symbol)
        DomainResult.success(instrument?.let { CsvImportService.InstrumentRef(it.instrumentId) })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (ex: Throwable) {
        DomainResult.failure(ex)
    }

    override suspend fun findByAlias(alias: String, source: String): DomainResult<CsvImportService.InstrumentRef?> = try {
        val instrument = instrumentRepository.findAlias(alias, source)?.let {
            instrumentRepository.findById(it.instrumentId)
        }
        DomainResult.success(instrument?.let { CsvImportService.InstrumentRef(it.instrumentId) })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (ex: Throwable) {
        DomainResult.failure(ex)
    }
}

private class DatabaseTradeLookup(
    private val tradeRepository: TradeRepository,
) : CsvImportService.TradeLookup {
    override suspend fun existsByExternalId(portfolioId: UUID, externalId: String): DomainResult<Boolean> = try {
        val exists = tradeRepository.findByExternalId(externalId)?.portfolioId == portfolioId
        DomainResult.success(exists)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (ex: Throwable) {
        DomainResult.failure(ex)
    }

    override suspend fun existsBySoftKey(key: CsvImportService.SoftTradeKey): DomainResult<Boolean> = try {
        val exists = TestDb.tx {
            TradesTable
                .selectAll()
                .where {
                    (TradesTable.portfolioId eq key.portfolioId) and
                        (TradesTable.instrumentId eq key.instrumentId) and
                        (TradesTable.datetime eq key.executedAt.toDbTimestamp()) and
                        (TradesTable.side eq key.side.name) and
                        (TradesTable.quantity eq key.quantity) and
                        (TradesTable.price eq key.price)
                }
                .limit(1)
                .any()
        }
        DomainResult.success(exists)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (ex: Throwable) {
        DomainResult.failure(ex)
    }
}

private fun Instant.toUtcTimestamp(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
