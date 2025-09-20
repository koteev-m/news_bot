package it

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import model.PositionDto
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import portfolio.model.Money
import portfolio.model.TradeSide
import portfolio.service.PositionCalc
import repo.InstrumentRepository
import repo.PortfolioRepository
import repo.PositionRepository
import repo.TradeRepository
import repo.model.NewInstrument
import repo.model.NewPortfolio
import repo.model.NewTrade
import repo.tables.PositionsTable

class PortfolioRepositoryIT {
    @Test
    fun `repositories persist trades and positions with deduplication`() = runBlocking {
        TestDb.withMigratedDatabase { _ ->
            val portfolioRepository = PortfolioRepository()
            val instrumentRepository = InstrumentRepository()
            val tradeRepository = TradeRepository()
            val positionRepository = PositionRepository()

            val createdAt = Instant.parse("2024-01-10T10:00:00Z")
            val userId = TestDb.createUser(telegramUserId = 123_456L)

            val portfolio = portfolioRepository.create(
                NewPortfolio(
                    userId = userId,
                    name = "Main",
                    baseCurrency = "RUB",
                    createdAt = createdAt,
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
                    createdAt = createdAt,
                ),
            )

            val calc = PositionCalc.AverageCostCalc()
            var positionState = PositionCalc.Position.empty("RUB")

            suspend fun recordTrade(
                extId: String?,
                executedAt: Instant,
                side: TradeSide,
                quantity: BigDecimal,
                price: BigDecimal,
            ) = run {
                val moneyPrice = Money.of(price, "RUB")
                val fees = Money.zero("RUB")
                val previousState = positionState
                positionState = when (side) {
                    TradeSide.BUY -> calc.applyBuy(positionState, quantity, moneyPrice, fees).position
                    TradeSide.SELL -> calc.applySell(positionState, quantity, moneyPrice, fees).position
                }

                try {
                    val trade = tradeRepository.createTrade(
                        NewTrade(
                            portfolioId = portfolio.portfolioId,
                            instrumentId = instrument.instrumentId,
                            datetime = executedAt,
                            side = side.name,
                            quantity = quantity,
                            price = price,
                            priceCurrency = "RUB",
                            fee = BigDecimal.ZERO,
                            feeCurrency = "RUB",
                            tax = null,
                            taxCurrency = null,
                            broker = "Test Broker",
                            note = "${side.name} $quantity",
                            extId = extId,
                            createdAt = executedAt.plusSeconds(5),
                        ),
                    )

                    val averagePrice = positionState.averagePrice
                    positionRepository.save(
                        PositionDto(
                            portfolioId = portfolio.portfolioId,
                            instrumentId = instrument.instrumentId,
                            qty = positionState.quantity,
                            avgPrice = averagePrice?.amount,
                            avgPriceCcy = averagePrice?.currency,
                            updatedAt = executedAt.plusSeconds(5),
                        ),
                    )

                    trade
                } catch (ex: Throwable) {
                    positionState = previousState
                    throw ex
                }
            }

            val firstTradeTime = createdAt
            val secondTradeTime = createdAt.plusSeconds(60)
            val thirdTradeTime = createdAt.plusSeconds(120)

            val firstTrade = recordTrade("ext-1", firstTradeTime, TradeSide.BUY, BigDecimal("10"), BigDecimal("250.00"))
            val secondTrade = recordTrade(null, secondTradeTime, TradeSide.BUY, BigDecimal("5"), BigDecimal("255.00"))
            val thirdTrade = recordTrade("ext-3", thirdTradeTime, TradeSide.SELL, BigDecimal("4"), BigDecimal("260.00"))

            val storedPosition = positionRepository.find(portfolio.portfolioId, instrument.instrumentId)
            assertNotNull(storedPosition)
            checkNotNull(storedPosition)
            assertEquals(positionState.quantity, storedPosition.qty)
            assertEquals(positionState.averagePrice?.amount, storedPosition.avgPrice)
            assertEquals(positionState.averagePrice?.currency, storedPosition.avgPriceCcy)

            val listedPositions = positionRepository.list(portfolio.portfolioId, limit = 10)
            assertEquals(1, listedPositions.size)
            assertEquals(storedPosition, listedPositions.single())

            val firstPageTrades = tradeRepository.listByPortfolio(portfolio.portfolioId, limit = 2)
            assertEquals(listOf(thirdTrade.tradeId, secondTrade.tradeId), firstPageTrades.map { it.tradeId })

            val secondPageTrades = tradeRepository.listByPortfolio(portfolio.portfolioId, limit = 2, offset = 1)
            assertEquals(listOf(secondTrade.tradeId, firstTrade.tradeId), secondPageTrades.map { it.tradeId })

            val periodTrades = tradeRepository.listByPeriod(
                portfolio.portfolioId,
                from = secondTradeTime.minusSeconds(1),
                to = thirdTradeTime.minusSeconds(1),
                limit = 10,
            )
            assertEquals(listOf(secondTrade.tradeId), periodTrades.map { it.tradeId })

            val instrumentTrades = tradeRepository.listByInstrument(instrument.instrumentId, limit = 10)
            assertEquals(3, instrumentTrades.size)

            val tradeByExtId = tradeRepository.findByExternalId("ext-1")
            assertNotNull(tradeByExtId)
            assertEquals(firstTrade.tradeId, tradeByExtId?.tradeId)

            val duplicateExtId = runCatching {
                recordTrade("ext-1", firstTradeTime, TradeSide.BUY, BigDecimal("10"), BigDecimal("250.00"))
            }
            assertTrue(duplicateExtId.isFailure)
            assertTrue(duplicateExtId.exceptionOrNull() is ExposedSQLException)

            val duplicateSoftKey = runCatching {
                recordTrade(null, secondTradeTime, TradeSide.BUY, BigDecimal("5"), BigDecimal("255.00"))
            }
            assertTrue(duplicateSoftKey.isFailure)
            assertTrue(duplicateSoftKey.exceptionOrNull() is ExposedSQLException)

            val allTrades = tradeRepository.listByPortfolio(portfolio.portfolioId, limit = 10)
            assertEquals(3, allTrades.size)

            val positionRowCount = TestDb.tx {
                PositionsTable
                    .selectAll()
                    .where { PositionsTable.portfolioId eq portfolio.portfolioId }
                    .count()
            }
            assertEquals(1, positionRowCount)
        }
    }
}
