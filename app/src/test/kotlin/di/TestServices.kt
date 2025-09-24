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
import portfolio.service.ReportService
import portfolio.service.ValuationService
import routes.PortfolioValuationReportServices
import routes.quotes.Services as QuoteServices
import routes.Services as ValuationReportRouteServices

fun Application.installTestServices(overrides: Services.() -> Unit = {}) {
    val module = installPortfolioModule()
    val baseCurrency = module.settings.pricing.baseCurrency

    val fxRateService = FxRateService(StubFxRateRepository())
    val pricingService = stubPricingService(fxRateService)
    val valuationService = stubValuationService(baseCurrency, pricingService, fxRateService)
    val reportService = stubReportService(baseCurrency)

    val services = Services(
        pricingService = pricingService,
        valuationService = valuationService,
        reportService = reportService,
    ).apply(overrides)

    attributes.put(Services.Key, services)
    attributes.put(QuoteServices.Key, services.pricingService)
    attributes.put(
        ValuationReportRouteServices.Key,
        PortfolioValuationReportServices(
            valuationService = services.valuationService,
            reportService = services.reportService,
        ),
    )
}

data class Services(
    var pricingService: PricingService,
    var valuationService: ValuationService,
    var reportService: ReportService,
) {
    companion object {
        val Key: AttributeKey<Services> = AttributeKey("TestStubServices")
    }
}

private fun stubPricingService(fxRateService: FxRateService): PricingService {
    val price = Money.of(BigDecimal("123.45000000"), "RUB")
    val provider = StubPriceProvider(price)
    return PricingService(
        moexProvider = provider,
        coingeckoProvider = provider,
        fxRateService = fxRateService,
        config = PricingService.Config(baseCurrency = price.currency),
    )
}

private fun stubValuationService(
    baseCurrency: String,
    pricingService: PricingService,
    fxRateService: FxRateService,
): ValuationService {
    val storage = StubValuationStorage()
    return ValuationService(
        storage = storage,
        pricingService = pricingService,
        fxRateService = fxRateService,
        baseCurrency = baseCurrency,
    )
}

private fun stubReportService(baseCurrency: String): ReportService {
    val storage = StubReportStorage()
    return ReportService(
        storage = storage,
        clock = Clock.systemUTC(),
        baseCurrency = baseCurrency,
    )
}

private class StubFxRateRepository : FxRateRepository {
    override suspend fun findOnOrBefore(ccy: String, timestamp: Instant): FxRate? =
        FxRate(
            ccy = ccy.uppercase(),
            ts = Instant.EPOCH,
            rateRub = BigDecimal.ONE,
            source = "test",
        )
}

private class StubPriceProvider(private val value: Money) : MoexPriceProvider, CoingeckoPriceProvider {
    override suspend fun closePrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
        DomainResult.success(value)

    override suspend fun lastPrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
        DomainResult.success(value)
}

private class StubValuationStorage : ValuationService.Storage {
    override suspend fun listPositions(portfolioId: UUID): DomainResult<List<ValuationService.Storage.PositionSnapshot>> =
        DomainResult.success(emptyList())

    override suspend fun latestValuationBefore(
        portfolioId: UUID,
        date: LocalDate,
    ): DomainResult<ValuationService.Storage.ValuationRecord?> =
        DomainResult.success(null)

    override suspend fun upsertValuation(
        record: ValuationService.Storage.ValuationRecord,
    ): DomainResult<ValuationService.Storage.ValuationRecord> =
        DomainResult.success(record)
}

private class StubReportStorage : ReportService.Storage {
    override suspend fun valuationMethod(portfolioId: UUID): DomainResult<ValuationMethod> =
        DomainResult.success(ValuationMethod.AVERAGE)

    override suspend fun listValuations(
        portfolioId: UUID,
        range: DateRange,
    ): DomainResult<List<ReportService.Storage.ValuationRecord>> =
        DomainResult.success(emptyList())

    override suspend fun latestValuationBefore(
        portfolioId: UUID,
        date: LocalDate,
    ): DomainResult<ReportService.Storage.ValuationRecord?> =
        DomainResult.success(null)

    override suspend fun listRealizedPnl(
        portfolioId: UUID,
        range: DateRange,
    ): DomainResult<List<ReportService.Storage.RealizedTrade>> =
        DomainResult.success(emptyList())

    override suspend fun listHoldings(
        portfolioId: UUID,
        asOf: LocalDate,
    ): DomainResult<List<ReportService.Storage.Holding>> =
        DomainResult.success(emptyList())
}
