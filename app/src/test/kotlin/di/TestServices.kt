package di

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import model.FxRate
import portfolio.errors.DomainResult
import portfolio.model.DateRange
import portfolio.model.Money
import portfolio.model.ValuationMethod
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import portfolio.service.MoexPriceProvider
import portfolio.service.PricingService
import portfolio.service.PortfolioService
import portfolio.service.ReportService
import portfolio.service.ValuationService
import routes.PortfolioValuationReportServices
import routes.Services as ValuationReportServicesRegistry
import routes.quotes.Services as QuoteServices

fun Application.installTestServices(overrides: TestServicesBuilder.() -> Unit = {}) {
    val module = installPortfolioModule()
    val builder = TestServicesBuilder(module)
    builder.overrides()
    val services = builder.build()

    attributes.put(Services.Key, services)
    attributes.put(QuoteServices.Key, services.pricingService)
    attributes.put(ValuationReportServicesRegistry.Key, builder.valuationReportServices())
}

object Services {
    val Key: AttributeKey<PortfolioModule.Services> = AttributeKey("TestPortfolioServices")
}

class TestServicesBuilder(private val module: PortfolioModule) {
    private val baseCurrency: String = module.settings.pricing.baseCurrency
    private val defaultPrice: Money = Money.of(BigDecimal("123.45"), baseCurrency)

    private var fxRateServiceBacking: FxRateService? = null
    private var pricingServiceBacking: PricingService? = null
    private var valuationServiceBacking: ValuationService? = null
    private var portfolioServiceBacking: PortfolioService? = null
    private var reportServiceBacking: ReportService? = null

    var fxRateService: FxRateService
        get() = fxRateServiceBacking ?: defaultFxRateService().also { fxRateServiceBacking = it }
        set(value) {
            fxRateServiceBacking = value
        }

    var pricingService: PricingService
        get() = pricingServiceBacking ?: defaultPricingService(fxRateService).also { pricingServiceBacking = it }
        set(value) {
            pricingServiceBacking = value
        }

    var valuationService: ValuationService
        get() = valuationServiceBacking ?: defaultValuationService(pricingService, fxRateService)
            .also { valuationServiceBacking = it }
        set(value) {
            valuationServiceBacking = value
        }

    var portfolioService: PortfolioService
        get() = portfolioServiceBacking ?: defaultPortfolioService().also { portfolioServiceBacking = it }
        set(value) {
            portfolioServiceBacking = value
        }

    var reportService: ReportService
        get() = reportServiceBacking ?: defaultReportService().also { reportServiceBacking = it }
        set(value) {
            reportServiceBacking = value
        }

    fun build(): PortfolioModule.Services = PortfolioModule.Services(
        fxRateService = fxRateService,
        pricingService = pricingService,
        valuationService = valuationService,
        portfolioService = portfolioService,
    )

    fun valuationReportServices(): PortfolioValuationReportServices = PortfolioValuationReportServices(
        valuationService = valuationService,
        reportService = reportService,
    )

    private fun defaultFxRateService(): FxRateService = FxRateService(
        object : FxRateRepository {
            override suspend fun findOnOrBefore(ccy: String, timestamp: Instant): FxRate? = null
        },
    )

    private fun defaultPricingService(fxService: FxRateService): PricingService = PricingService(
        moexProvider = ConstantPriceProvider(defaultPrice),
        coingeckoProvider = ConstantPriceProvider(defaultPrice),
        fxRateService = fxService,
        config = PricingService.Config(baseCurrency = baseCurrency),
    )

    private fun defaultValuationService(
        pricing: PricingService,
        fxService: FxRateService,
    ): ValuationService = ValuationService(
        storage = StubValuationStorage(baseCurrency),
        pricingService = pricing,
        fxRateService = fxService,
        baseCurrency = baseCurrency,
    )

    private fun defaultPortfolioService(): PortfolioService = PortfolioService(
        storage = StubPortfolioStorage(),
        clock = Clock.systemUTC(),
    )

    private fun defaultReportService(): ReportService = ReportService(
        storage = StubReportStorage(baseCurrency),
        baseCurrency = baseCurrency,
    )
}

private class ConstantPriceProvider(private val value: Money) : MoexPriceProvider, CoingeckoPriceProvider {
    override suspend fun closePrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
        DomainResult.success(value)

    override suspend fun lastPrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
        DomainResult.success(value)
}

private class StubValuationStorage(private val currency: String) : ValuationService.Storage {
    override suspend fun listPositions(portfolioId: UUID): DomainResult<List<ValuationService.Storage.PositionSnapshot>> =
        DomainResult.success(emptyList())

    override suspend fun latestValuationBefore(
        portfolioId: UUID,
        date: LocalDate,
    ): DomainResult<ValuationService.Storage.ValuationRecord?> = DomainResult.success(null)

    override suspend fun upsertValuation(
        record: ValuationService.Storage.ValuationRecord,
    ): DomainResult<ValuationService.Storage.ValuationRecord> = DomainResult.success(record)
}

private class StubPortfolioStorage : PortfolioService.Storage {
    override suspend fun <T> transaction(
        block: suspend PortfolioService.Storage.Transaction.() -> DomainResult<T>,
    ): DomainResult<T> = try {
        block(StubTransaction())
    } catch (throwable: Throwable) {
        DomainResult.failure(throwable)
    }

    override suspend fun listPositions(portfolioId: UUID): DomainResult<List<PortfolioService.PositionSummary>> =
        DomainResult.success(emptyList())

    private class StubTransaction : PortfolioService.Storage.Transaction {
        override suspend fun loadPosition(
            portfolioId: UUID,
            instrumentId: Long,
            method: ValuationMethod,
        ): DomainResult<PortfolioService.StoredPosition?> = DomainResult.success(null)

        override suspend fun savePosition(position: PortfolioService.StoredPosition): DomainResult<Unit> =
            DomainResult.success(Unit)

        override suspend fun recordTrade(trade: PortfolioService.StoredTrade): DomainResult<Unit> =
            DomainResult.success(Unit)
    }
}

private class StubReportStorage(private val currency: String) : ReportService.Storage {
    override suspend fun valuationMethod(portfolioId: UUID): DomainResult<ValuationMethod> =
        DomainResult.success(ValuationMethod.AVERAGE)

    override suspend fun listValuations(
        portfolioId: UUID,
        range: DateRange,
    ): DomainResult<List<ReportService.Storage.ValuationRecord>> = DomainResult.success(
        listOf(
            ReportService.Storage.ValuationRecord(
                date = range.from,
                value = Money.zero(currency),
                pnlDay = Money.zero(currency),
                pnlTotal = Money.zero(currency),
                drawdown = BigDecimal.ZERO,
            ),
        ),
    )

    override suspend fun latestValuationBefore(
        portfolioId: UUID,
        date: LocalDate,
    ): DomainResult<ReportService.Storage.ValuationRecord?> = DomainResult.success(null)

    override suspend fun listRealizedPnl(
        portfolioId: UUID,
        range: DateRange,
    ): DomainResult<List<ReportService.Storage.RealizedTrade>> = DomainResult.success(emptyList())

    override suspend fun listHoldings(
        portfolioId: UUID,
        asOf: LocalDate,
    ): DomainResult<List<ReportService.Storage.Holding>> = DomainResult.success(emptyList())
}
