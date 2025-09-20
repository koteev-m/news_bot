package di

import db.DatabaseFactory
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.OffsetDateTime
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import portfolio.errors.DomainResult
import portfolio.model.Money
import portfolio.model.ValuationMethod
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.FxRateService
import portfolio.service.MoexPriceProvider
import portfolio.service.PortfolioService
import portfolio.service.PositionCalc
import portfolio.service.PricingService
import portfolio.service.ValuationService
import repo.FxRateRepository
import repo.InstrumentRepository
import repo.PortfolioRepository
import repo.PositionRepository
import repo.TradeRepository
import repo.ValuationRepository
import repo.mapper.toValuationDailyRecord
import repo.tables.InstrumentsTable
import repo.tables.PositionsTable
import repo.tables.PricesTable
import repo.tables.TradesTable
import repo.tables.ValuationsDailyTable
import repo.model.NewValuationDaily
import repo.model.ValuationDailyRecord

private val PortfolioModuleKey = AttributeKey<PortfolioModule>("PortfolioModule")

data class PortfolioModule(
    val repositories: Repositories,
    val services: Services,
    val settings: Settings,
) {
    data class Repositories(
        val portfolioRepository: PortfolioRepository,
        val instrumentRepository: InstrumentRepository,
        val tradeRepository: TradeRepository,
        val positionRepository: PositionRepository,
        val fxRateRepository: FxRateRepository,
        val valuationRepository: ValuationRepository,
    )

    data class Services(
        val fxRateService: FxRateService,
        val pricingService: PricingService,
        val valuationService: ValuationService,
        val portfolioService: PortfolioService,
    )

    data class Settings(
        val pricing: Pricing,
        val portfolio: Portfolio,
    ) {
        data class Pricing(
            val preferClosePrice: Boolean,
            val fallbackToLast: Boolean,
            val baseCurrency: String,
        )

        data class Portfolio(
            val defaultValuationMethod: ValuationMethod,
            val autoCreateInstruments: Boolean,
        )
    }
}

fun Application.installPortfolioModule(): PortfolioModule {
    if (attributes.contains(PortfolioModuleKey)) {
        return attributes[PortfolioModuleKey]
    }

    val config = environment.config
    val pricingPreferClose = config.getBoolean("pricing.preferClosePrice", default = true)
    val pricingFallback = config.getBoolean("pricing.fallbackToLast", default = true)
    val defaultMethod = config.getValuationMethod("portfolio.defaultValuationMethod", ValuationMethod.AVERAGE)
    val autoCreateInstruments = config.getBoolean("portfolio.autoCreateInstruments", default = true)

    val pricingConfig = PricingService.Config(
        preferClosePrice = pricingPreferClose,
        fallbackToLast = pricingFallback,
    )

    val repositories = PortfolioModule.Repositories(
        portfolioRepository = PortfolioRepository(),
        instrumentRepository = InstrumentRepository(),
        tradeRepository = TradeRepository(),
        positionRepository = PositionRepository(),
        fxRateRepository = FxRateRepository(),
        valuationRepository = ValuationRepository(),
    )

    val fxRateService = FxRateService(repositories.fxRateRepository)
    val priceRepository = PriceRepository()
    val moexProvider = MoexDatabasePriceProvider(priceRepository)
    val coingeckoProvider = CoingeckoDatabasePriceProvider(priceRepository)
    val pricingService = PricingService(
        moexProvider = moexProvider,
        coingeckoProvider = coingeckoProvider,
        fxRateService = fxRateService,
        config = pricingConfig,
    )

    val valuationStorage = DatabaseValuationStorage(
        positionRepository = repositories.positionRepository,
        valuationRepository = repositories.valuationRepository,
    )
    val valuationService = ValuationService(
        storage = valuationStorage,
        pricingService = pricingService,
        fxRateService = fxRateService,
    )

    val portfolioStorage = DatabasePortfolioStorage(
        positionRepository = repositories.positionRepository,
        instrumentRepository = repositories.instrumentRepository,
        defaultValuationMethod = defaultMethod,
        fallbackCurrency = pricingConfig.baseCurrency,
        clock = Clock.systemUTC(),
    )
    val portfolioService = PortfolioService(portfolioStorage)

    val settings = PortfolioModule.Settings(
        pricing = PortfolioModule.Settings.Pricing(
            preferClosePrice = pricingPreferClose,
            fallbackToLast = pricingFallback,
            baseCurrency = pricingConfig.baseCurrency,
        ),
        portfolio = PortfolioModule.Settings.Portfolio(
            defaultValuationMethod = defaultMethod,
            autoCreateInstruments = autoCreateInstruments,
        ),
    )

    val module = PortfolioModule(
        repositories = repositories,
        services = PortfolioModule.Services(
            fxRateService = fxRateService,
            pricingService = pricingService,
            valuationService = valuationService,
            portfolioService = portfolioService,
        ),
        settings = settings,
    )

    attributes.put(PortfolioModuleKey, module)
    return module
}

fun Application.portfolioModule(): PortfolioModule = attributes[PortfolioModuleKey]

private fun ApplicationConfig.getBoolean(path: String, default: Boolean): Boolean {
    val raw = propertyOrNull(path)?.getString()?.trim() ?: return default
    return when (raw.lowercase()) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> default
    }
}

private fun ApplicationConfig.getValuationMethod(path: String, fallback: ValuationMethod): ValuationMethod {
    val raw = propertyOrNull(path)?.getString()?.trim() ?: return fallback
    return try {
        ValuationMethod.valueOf(raw.uppercase())
    } catch (ex: IllegalArgumentException) {
        fallback
    }
}

private class PriceRepository(
    private val zoneId: ZoneId = ZoneOffset.UTC,
) {
    suspend fun findClosePrice(source: String, instrumentId: Long, date: LocalDate): Money? =
        DatabaseFactory.dbQuery {
            val start = date.atStartOfDay(zoneId).toInstant().toUtcTimestamp()
            val endExclusive = date.plusDays(1).atStartOfDay(zoneId).toInstant().toUtcTimestamp()
            PricesTable
                .selectAll()
                .where {
                    (PricesTable.instrumentId eq instrumentId) and
                        (PricesTable.sourceCol eq source) and
                        (PricesTable.ts greaterEq start) and
                        (PricesTable.ts less endExclusive)
                }
                .orderBy(PricesTable.ts, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.toMoney()
        }

    suspend fun findLastPrice(source: String, instrumentId: Long, date: LocalDate): Money? =
        DatabaseFactory.dbQuery {
            val endExclusive = date.plusDays(1).atStartOfDay(zoneId).toInstant().toUtcTimestamp()
            PricesTable
                .selectAll()
                .where {
                    (PricesTable.instrumentId eq instrumentId) and
                        (PricesTable.sourceCol eq source) and
                        (PricesTable.ts less endExclusive)
                }
                .orderBy(PricesTable.ts, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.toMoney()
        }

    private fun ResultRow.toMoney(): Money = Money.of(this[PricesTable.price], this[PricesTable.ccy])
}

private open class StoredPriceProvider(
    private val source: String,
    private val priceRepository: PriceRepository,
) : portfolio.service.PriceProvider {
    override suspend fun closePrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
        runCatching { priceRepository.findClosePrice(source, instrumentId, on) }
            .fold(onSuccess = { DomainResult.success(it) }, onFailure = { DomainResult.failure(it) })

    override suspend fun lastPrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
        runCatching { priceRepository.findLastPrice(source, instrumentId, on) }
            .fold(onSuccess = { DomainResult.success(it) }, onFailure = { DomainResult.failure(it) })
}

private class MoexDatabasePriceProvider(priceRepository: PriceRepository) :
    StoredPriceProvider(MOEX_SOURCE, priceRepository),
    MoexPriceProvider

private class CoingeckoDatabasePriceProvider(priceRepository: PriceRepository) :
    StoredPriceProvider(COINGECKO_SOURCE, priceRepository),
    CoingeckoPriceProvider

private class DatabaseValuationStorage(
    private val positionRepository: PositionRepository,
    private val valuationRepository: ValuationRepository,
) : ValuationService.Storage {
    override suspend fun listPositions(portfolioId: java.util.UUID): DomainResult<List<ValuationService.Storage.PositionSnapshot>> =
        runCatching {
            positionRepository.list(portfolioId, Int.MAX_VALUE).map { dto ->
                ValuationService.Storage.PositionSnapshot(
                    instrumentId = dto.instrumentId,
                    quantity = dto.qty,
                    averagePrice = dto.avgPrice?.let { price ->
                        val currency = dto.avgPriceCcy
                        currency?.let { Money.of(price, it) }
                    },
                )
            }
        }.fold(
            onSuccess = { DomainResult.success(it) },
            onFailure = { DomainResult.failure(it) },
        )

    override suspend fun latestValuationBefore(
        portfolioId: java.util.UUID,
        date: LocalDate,
    ): DomainResult<ValuationService.Storage.ValuationRecord?> =
        runCatching {
            DatabaseFactory.dbQuery {
                ValuationsDailyTable
                    .selectAll()
                    .where {
                        (ValuationsDailyTable.portfolioId eq portfolioId) and
                            (ValuationsDailyTable.date less date)
                    }
                    .orderBy(ValuationsDailyTable.date, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?.toValuationDailyRecord()
                    ?.toStorageRecord()
            }
        }.fold(
            onSuccess = { DomainResult.success(it) },
            onFailure = { DomainResult.failure(it) },
        )

    override suspend fun upsertValuation(
        record: ValuationService.Storage.ValuationRecord,
    ): DomainResult<ValuationService.Storage.ValuationRecord> =
        runCatching {
            valuationRepository.upsert(
                NewValuationDaily(
                    portfolioId = record.portfolioId,
                    date = record.date,
                    valueRub = record.valueRub,
                    pnlDay = record.pnlDay,
                    pnlTotal = record.pnlTotal,
                    drawdown = record.drawdown,
                ),
            ).toStorageRecord()
        }.fold(
            onSuccess = { DomainResult.success(it) },
            onFailure = { DomainResult.failure(it) },
        )

    private fun ValuationDailyRecord.toStorageRecord(): ValuationService.Storage.ValuationRecord =
        ValuationService.Storage.ValuationRecord(
            portfolioId = portfolioId,
            date = date,
            valueRub = valueRub,
            pnlDay = pnlDay,
            pnlTotal = pnlTotal,
            drawdown = drawdown,
        )
}

private class DatabasePortfolioStorage(
    private val positionRepository: PositionRepository,
    private val instrumentRepository: InstrumentRepository,
    private val defaultValuationMethod: ValuationMethod,
    private val fallbackCurrency: String,
    private val clock: Clock,
) : PortfolioService.Storage {
    override suspend fun <T> transaction(
        block: suspend PortfolioService.Storage.Transaction.() -> DomainResult<T>,
    ): DomainResult<T> =
        runCatching {
            DatabaseFactory.dbQuery {
                val tx = TransactionImpl(fallbackCurrency, clock)
                tx.block()
            }
        }.fold(
            onSuccess = { it },
            onFailure = { DomainResult.failure(it) },
        )

    override suspend fun listPositions(portfolioId: java.util.UUID): DomainResult<List<PortfolioService.PositionSummary>> =
        runCatching {
            positionRepository.list(portfolioId, Int.MAX_VALUE).map { dto ->
                val instrument = instrumentRepository.findById(dto.instrumentId)
                val instrumentName = instrument?.symbol ?: "Instrument ${dto.instrumentId}"
                val avgPriceMoney = dto.avgPrice?.let { price ->
                    val currency = dto.avgPriceCcy ?: instrument?.currency ?: fallbackCurrency
                    Money.of(price, currency)
                }
                PortfolioService.PositionSummary(
                    portfolioId = dto.portfolioId,
                    instrumentId = dto.instrumentId,
                    instrumentName = instrumentName,
                    quantity = dto.qty,
                    averagePrice = avgPriceMoney,
                    valuationMethod = defaultValuationMethod,
                )
            }
        }.fold(
            onSuccess = { DomainResult.success(it) },
            onFailure = { DomainResult.failure(it) },
        )

    private inner class TransactionImpl(
        private val fallbackCurrency: String,
        private val clock: Clock,
    ) : PortfolioService.Storage.Transaction {
        override suspend fun loadPosition(
            portfolioId: java.util.UUID,
            instrumentId: Long,
            method: ValuationMethod,
        ): DomainResult<PortfolioService.StoredPosition?> =
            runCatching {
                PositionsTable
                    .selectAll()
                    .where {
                        (PositionsTable.portfolioId eq portfolioId) and (PositionsTable.instrumentId eq instrumentId)
                    }
                    .limit(1)
                    .singleOrNull()
                    ?.let { row ->
                        val quantity = row[PositionsTable.qty]
                        val avgPrice = row[PositionsTable.avgPrice]
                        val avgCurrency = row[PositionsTable.avgPriceCcy]
                        val currency = avgCurrency ?: instrumentCurrency(instrumentId) ?: fallbackCurrency
                        val costAmount = if (avgPrice != null) avgPrice.multiply(quantity) else BigDecimal.ZERO
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
            }.fold(
                onSuccess = { DomainResult.success(it) },
                onFailure = { DomainResult.failure(it) },
            )

        override suspend fun savePosition(
            position: PortfolioService.StoredPosition,
        ): DomainResult<Unit> =
            runCatching {
                val predicate =
                    (PositionsTable.portfolioId eq position.portfolioId) and (PositionsTable.instrumentId eq position.instrumentId)
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
            }.fold(
                onSuccess = { DomainResult.success(Unit) },
                onFailure = { DomainResult.failure(it) },
            )

        override suspend fun recordTrade(trade: PortfolioService.StoredTrade): DomainResult<Unit> =
            runCatching {
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
            }.fold(
                onSuccess = { DomainResult.success(Unit) },
                onFailure = { DomainResult.failure(it) },
            )

        private fun instrumentCurrency(instrumentId: Long): String? =
            InstrumentsTable
                .selectAll()
                .where { InstrumentsTable.instrumentId eq instrumentId }
                .limit(1)
                .singleOrNull()
                ?.get(InstrumentsTable.currency)
    }
}

private const val MOEX_SOURCE = "MOEX"
private const val COINGECKO_SOURCE = "COINGECKO"

private fun Instant.toUtcTimestamp(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
