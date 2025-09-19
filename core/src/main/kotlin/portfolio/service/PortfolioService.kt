package portfolio.service

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.Money
import portfolio.model.TradeSide
import portfolio.model.TradeView
import portfolio.model.ValuationMethod

class PortfolioService(
    private val storage: Storage,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun applyTrade(trade: TradeView, method: ValuationMethod): DomainResult<Unit> {
        val validationError = validateTrade(trade)
        if (validationError != null) {
            return failure(validationError)
        }

        val totalFees = totalFees(trade)
        val quantity = trade.quantity

        val mutex = portfolioLocks.computeIfAbsent(trade.portfolioId) { Mutex() }
        val result = mutex.withLock {
            storage.transaction {
                processTrade(this, trade, method, quantity, totalFees)
            }
        }

        return result.map { Unit }
    }

    private fun validateTrade(trade: TradeView): PortfolioError? {
        if (trade.quantity <= BigDecimal.ZERO) {
            return PortfolioError.Validation("Trade quantity must be positive")
        }
        if (trade.fee.currency != trade.price.currency) {
            return PortfolioError.Validation("Fee currency must match trade currency")
        }
        if (trade.fee.amount < BigDecimal.ZERO) {
            return PortfolioError.Validation("Fee amount cannot be negative")
        }
        val tax = trade.tax
        if (tax != null) {
            if (tax.currency != trade.price.currency) {
                return PortfolioError.Validation("Tax currency must match trade currency")
            }
            if (tax.amount < BigDecimal.ZERO) {
                return PortfolioError.Validation("Tax amount cannot be negative")
            }
        }
        if (trade.notional.currency != trade.price.currency) {
            return PortfolioError.Validation("Notional currency must match trade currency")
        }
        return null
    }

    private fun totalFees(trade: TradeView): Money {
        val tax = trade.tax ?: zero(trade.price.currency)
        return trade.fee + tax
    }

    private suspend fun processTrade(
        transaction: Storage.Transaction,
        trade: TradeView,
        method: ValuationMethod,
        quantity: BigDecimal,
        fees: Money,
    ): DomainResult<Money?> {
        val loaded = transaction.loadPosition(trade.portfolioId, trade.instrumentId, method)
        if (loaded.isFailure) {
            return DomainResult.failure(loaded.exceptionOrNull()!!)
        }
        val stored = loaded.getOrNull()
        val currentPosition = if (stored == null) {
            PositionCalc.Position.empty(trade.price.currency)
        } else {
            val storedCurrency = stored.position.costBasis.currency
            if (storedCurrency != trade.price.currency) {
                return failure(
                    PortfolioError.Validation(
                        "Trade currency ${trade.price.currency} does not match position currency $storedCurrency",
                    ),
                )
            }
            stored.position
        }

        val calculator = calculators.getValue(method)
        val outcome = try {
            when (trade.side) {
                TradeSide.BUY -> calculator.applyBuy(currentPosition, quantity, trade.price, fees)
                TradeSide.SELL -> calculator.applySell(currentPosition, quantity, trade.price, fees)
            }
        } catch (iae: IllegalArgumentException) {
            return failure(PortfolioError.Validation(iae.message ?: "Invalid trade input"))
        }

        val storedPosition = StoredPosition(
            portfolioId = trade.portfolioId,
            instrumentId = trade.instrumentId,
            valuationMethod = method,
            position = outcome.position,
            updatedAt = clock.instant(),
        )

        val saveResult = transaction.savePosition(storedPosition)
        if (saveResult.isFailure) {
            return DomainResult.failure(saveResult.exceptionOrNull()!!)
        }

        val recordResult = transaction.recordTrade(
                StoredTrade(
                    tradeId = trade.tradeId,
                    portfolioId = trade.portfolioId,
                    instrumentId = trade.instrumentId,
                    tradeDate = trade.tradeDate,
                    executedAt = trade.executedAt,
                    side = trade.side,
                    quantity = quantity,
                    price = trade.price,
                    fee = trade.fee,
                    tax = trade.tax,
                    notional = trade.notional,
                    valuationMethod = method,
                    realizedPnl = if (trade.side == TradeSide.SELL) outcome.realizedPnl else null,
                    broker = trade.broker,
                    note = trade.note,
                    externalId = trade.externalId,
                ),
            StoredTrade(
                tradeId = trade.tradeId,
                portfolioId = trade.portfolioId,
                instrumentId = trade.instrumentId,
                tradeDate = trade.tradeDate,
                side = trade.side,
                quantity = quantity,
                price = trade.price,
                fee = trade.fee,
                tax = trade.tax,
                notional = trade.notional,
                valuationMethod = method,
                realizedPnl = if (trade.side == TradeSide.SELL) outcome.realizedPnl else null,
            ),
        )
        if (recordResult.isFailure) {
            return DomainResult.failure(recordResult.exceptionOrNull()!!)
        }

        return DomainResult.success(if (trade.side == TradeSide.SELL) outcome.realizedPnl else null)
    }

    private fun failure(error: PortfolioError): DomainResult<Nothing> =
        DomainResult.failure(PortfolioException(error))

    interface Storage {
        suspend fun <T> transaction(block: suspend Transaction.() -> DomainResult<T>): DomainResult<T>

        interface Transaction {
            suspend fun loadPosition(
                portfolioId: UUID,
                instrumentId: Long,
                method: ValuationMethod,
            ): DomainResult<StoredPosition?>

            suspend fun savePosition(position: StoredPosition): DomainResult<Unit>

            suspend fun recordTrade(trade: StoredTrade): DomainResult<Unit>
        }
    }

    data class StoredPosition(
        val portfolioId: UUID,
        val instrumentId: Long,
        val valuationMethod: ValuationMethod,
        val position: PositionCalc.Position,
        val updatedAt: Instant,
    )

    data class StoredTrade(
        val tradeId: UUID,
        val portfolioId: UUID,
        val instrumentId: Long,
        val tradeDate: java.time.LocalDate,
        val executedAt: Instant,
        val side: TradeSide,
        val quantity: BigDecimal,
        val price: Money,
        val fee: Money,
        val tax: Money?,
        val notional: Money,
        val valuationMethod: ValuationMethod,
        val realizedPnl: Money?,
        val broker: String?,
        val note: String?,
        val externalId: String?,
    )

    private fun zero(currency: String): Money = Money.of(BigDecimal.ZERO, currency)

    private val calculators: Map<ValuationMethod, PositionCalc> = mapOf(
        ValuationMethod.AVERAGE to PositionCalc.AverageCostCalc(),
        ValuationMethod.FIFO to PositionCalc.FifoCalc(),
    )

    private val portfolioLocks = ConcurrentHashMap<UUID, Mutex>()
}
