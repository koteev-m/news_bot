package routes

import common.runCatchingNonFatal
import db.DatabaseFactory
import di.portfolioModule
import integrations.integrationsModule
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.metrics.CbrFxConverter
import portfolio.metrics.MetricsPeriod
import portfolio.metrics.PortfolioMetricsReport
import portfolio.metrics.PortfolioMetricsService
import portfolio.model.DateRange
import portfolio.model.Money
import portfolio.model.ValuationMethod
import portfolio.service.ReportService
import portfolio.service.ValuationService
import repo.PortfolioRepository
import repo.ValuationRepository
import repo.mapper.toValuationDailyRecord
import repo.model.ValuationDailyRecord
import repo.tables.ValuationsDailyTable
import routes.dto.toResponse
import security.userIdOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import routes.dto.toResponse as toMetricsResponse

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val ACCEPT_CSV = Regex("(^|,|\\s)text/csv(\\s|;|,|\$)", RegexOption.IGNORE_CASE)

fun Route.portfolioValuationReportRoutes() {
    route("/api/portfolio/{id}") {
        post("/revalue") {
            val subject = call.userIdOrNull
            if (subject == null) {
                call.respondUnauthorized()
                return@post
            }

            val portfolioId = call.parsePortfolioId() ?: return@post
            val date = call.parseRequiredDateParam("date") ?: return@post

            val services = call.portfolioValuationReportServices()
            services.valuationService.revaluePortfolioOn(portfolioId, date).fold(
                onSuccess = { valuation -> call.respond(valuation.toResponse()) },
                onFailure = { error -> call.handleDomainError(error) },
            )
        }

        get("/report") {
            val subject = call.userIdOrNull
            if (subject == null) {
                call.respondUnauthorized()
                return@get
            }

            val portfolioId = call.parsePortfolioId() ?: return@get
            val periodParam = call.request.queryParameters["period"]?.trim()
            if (!periodParam.isNullOrEmpty()) {
                val period =
                    parsePeriod(periodParam) ?: run {
                        call.respondBadRequest(listOf("period must be daily, weekly, or monthly"))
                        return@get
                    }
                val services = call.portfolioValuationReportServices()
                val portfolio =
                    runCatchingNonFatal { services.loadPortfolio(portfolioId) }
                        .getOrElse { throwable -> call.handleDomainError(throwable) }
                        ?: run {
                            call.respondNotFound("Portfolio $portfolioId not found")
                            return@get
                        }
                val baseParam = call.request.queryParameters["base"]?.trim()
                val base = (baseParam?.takeIf { it.isNotEmpty() } ?: portfolio.baseCurrency).uppercase(Locale.ROOT)
                if (base !in ALLOWED_BASES) {
                    call.respondBadRequest(listOf("base must be one of: ${ALLOWED_BASES.joinToString(", ")}"))
                    return@get
                }
                services.metricsService.buildReport(portfolioId, base, period).fold(
                    onSuccess = { report ->
                        if (call.wantsCsv()) {
                            val filename = "portfolio_${portfolioId}_report_${period.name.lowercase()}_$base.csv"
                            call.response.headers.append(
                                HttpHeaders.ContentDisposition,
                                "attachment; filename=\"$filename\"",
                            )
                            call.respondText(
                                text = report.toCsv(),
                                contentType = ContentType.Text.CSV,
                            )
                        } else {
                            call.respond(report.toMetricsResponse())
                        }
                    },
                    onFailure = { error -> call.handleDomainError(error) },
                )
                return@get
            }

            val range = call.parseDateRange() ?: return@get

            val services = call.portfolioValuationReportServices()
            services.reportService.getPortfolioReport(portfolioId, range).fold(
                onSuccess = { report -> call.respond(report.toResponse()) },
                onFailure = { error -> call.handleDomainError(error) },
            )
        }
    }
}

object Services {
    val Key: AttributeKey<PortfolioValuationReportServices> =
        AttributeKey("PortfolioValuationReportServices")
}

data class PortfolioValuationReportServices(
    val valuationService: ValuationService,
    val reportService: ReportService,
    val metricsService: PortfolioMetricsService,
    val loadPortfolio: suspend (UUID) -> repo.model.PortfolioEntity?,
)

private suspend fun ApplicationCall.parsePortfolioId(): UUID? {
    val raw = parameters["id"]?.trim()
    if (raw.isNullOrEmpty()) {
        respondBadRequest(listOf("portfolioId must be a valid UUID"))
        return null
    }
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        respondBadRequest(listOf("portfolioId must be a valid UUID"))
        null
    }
}

private suspend fun ApplicationCall.parseRequiredDateParam(name: String): LocalDate? {
    val raw = request.queryParameters[name]?.trim()
    if (raw.isNullOrEmpty()) {
        respondBadRequest(listOf("$name is required in format YYYY-MM-DD"))
        return null
    }
    return try {
        LocalDate.parse(raw, DATE_FORMATTER)
    } catch (_: DateTimeParseException) {
        respondBadRequest(listOf("$name must be in format YYYY-MM-DD"))
        null
    }
}

private suspend fun ApplicationCall.parseDateRange(): DateRange? {
    val from = parseRequiredDateParam("from") ?: return null
    val to = parseRequiredDateParam("to") ?: return null
    if (to.isBefore(from)) {
        respondBadRequest(listOf("from must be on or before to"))
        return null
    }
    return DateRange(from = from, to = to)
}

private fun ApplicationCall.portfolioValuationReportServices(): PortfolioValuationReportServices {
    val attributes = application.attributes
    if (attributes.contains(Services.Key)) {
        return attributes[Services.Key]
    }

    val module = application.portfolioModule()
    val fxConverter = CbrFxConverter(application.integrationsModule().cbrXmlDailyClient)
    val services =
        PortfolioValuationReportServices(
            valuationService = module.services.valuationService,
            reportService =
            ReportService(
                storage =
                DatabaseReportStorage(
                    portfolioRepository = module.repositories.portfolioRepository,
                    valuationRepository = module.repositories.valuationRepository,
                    defaultValuationMethod = module.settings.portfolio.defaultValuationMethod,
                ),
                baseCurrency = module.settings.pricing.baseCurrency,
            ),
            metricsService =
            PortfolioMetricsService(
                storage =
                DatabaseMetricsStorage(
                    tradeRepository = module.repositories.tradeRepository,
                    valuationRepository = module.repositories.valuationRepository,
                ),
                fxConverter = fxConverter,
            ),
            loadPortfolio = { id -> module.repositories.portfolioRepository.findById(id) },
        )
    attributes.put(Services.Key, services)
    return services
}

private class DatabaseReportStorage(
    private val portfolioRepository: PortfolioRepository,
    private val valuationRepository: ValuationRepository,
    private val defaultValuationMethod: ValuationMethod,
) : ReportService.Storage {
    override suspend fun valuationMethod(portfolioId: UUID): DomainResult<ValuationMethod> {
        val portfolio =
            runCatchingNonFatal { portfolioRepository.findById(portfolioId) }
                .getOrElse { throwable -> return DomainResult.failure(throwable) }
        return if (portfolio == null) {
            DomainResult.failure(portfolioNotFound(portfolioId))
        } else {
            DomainResult.success(defaultValuationMethod)
        }
    }

    override suspend fun listValuations(
        portfolioId: UUID,
        range: DateRange,
    ): DomainResult<List<ReportService.Storage.ValuationRecord>> {
        val records =
            runCatchingNonFatal { valuationRepository.listRange(portfolioId, range, Int.MAX_VALUE) }
                .getOrElse { throwable -> return DomainResult.failure(throwable) }
        return DomainResult.success(records.map { it.toStorageRecord() })
    }

    override suspend fun latestValuationBefore(
        portfolioId: UUID,
        date: LocalDate,
    ): DomainResult<ReportService.Storage.ValuationRecord?> {
        val record =
            runCatchingNonFatal {
                DatabaseFactory.dbQuery {
                    ValuationsDailyTable
                        .selectAll()
                        .where {
                            (ValuationsDailyTable.portfolioId eq portfolioId) and
                                (ValuationsDailyTable.date less date)
                        }.orderBy(ValuationsDailyTable.date, SortOrder.DESC)
                        .limit(1)
                        .singleOrNull()
                        ?.toValuationDailyRecord()
                }
            }.getOrElse { throwable -> return DomainResult.failure(throwable) }
        return DomainResult.success(record?.toStorageRecord())
    }

    override suspend fun listRealizedPnl(
        portfolioId: UUID,
        range: DateRange,
    ): DomainResult<List<ReportService.Storage.RealizedTrade>> = DomainResult.success(emptyList())

    override suspend fun listHoldings(
        portfolioId: UUID,
        asOf: LocalDate,
    ): DomainResult<List<ReportService.Storage.Holding>> = DomainResult.success(emptyList())

    private fun ValuationDailyRecord.toStorageRecord(): ReportService.Storage.ValuationRecord =
        ReportService.Storage.ValuationRecord(
            date = date,
            value = Money.of(valueRub, BASE_CURRENCY),
            pnlDay = Money.of(pnlDay, BASE_CURRENCY),
            pnlTotal = Money.of(pnlTotal, BASE_CURRENCY),
            drawdown = drawdown,
        )

    private fun portfolioNotFound(portfolioId: UUID): PortfolioException =
        PortfolioException(PortfolioError.NotFound("Portfolio $portfolioId not found"))

    companion object {
        private const val BASE_CURRENCY = "RUB"
    }
}

private class DatabaseMetricsStorage(
    private val tradeRepository: repo.TradeRepository,
    private val valuationRepository: ValuationRepository,
) : PortfolioMetricsService.Storage {
    override suspend fun listValuations(
        portfolioId: UUID,
    ): DomainResult<List<PortfolioMetricsService.Storage.ValuationRecord>> {
        val records =
            runCatchingNonFatal { valuationRepository.list(portfolioId, Int.MAX_VALUE) }
                .getOrElse { throwable -> return DomainResult.failure(throwable) }
        return DomainResult.success(
            records
                .map { record ->
                    PortfolioMetricsService.Storage.ValuationRecord(
                        date = record.date,
                        valueRub = record.valueRub,
                    )
                }.sortedBy { it.date },
        )
    }

    override suspend fun listTrades(
        portfolioId: UUID,
    ): DomainResult<List<PortfolioMetricsService.Storage.TradeRecord>> {
        val records =
            runCatchingNonFatal { tradeRepository.listByPortfolio(portfolioId, Int.MAX_VALUE) }
                .getOrElse { throwable -> return DomainResult.failure(throwable) }
        return DomainResult.success(
            records.map { trade ->
                PortfolioMetricsService.Storage.TradeRecord(
                    datetime = trade.datetime,
                    side = trade.side,
                    quantity = trade.quantity,
                    price = trade.price,
                    priceCurrency = trade.priceCurrency,
                    fee = trade.fee,
                    feeCurrency = trade.feeCurrency,
                    tax = trade.tax,
                    taxCurrency = trade.taxCurrency,
                )
            },
        )
    }
}

private fun parsePeriod(raw: String): MetricsPeriod? =
    when (raw.trim().uppercase(Locale.ROOT)) {
        "DAILY" -> MetricsPeriod.DAILY
        "WEEKLY" -> MetricsPeriod.WEEKLY
        "MONTHLY" -> MetricsPeriod.MONTHLY
        else -> null
    }

private fun ApplicationCall.wantsCsv(): Boolean {
    val format = request.queryParameters["format"]?.trim()?.lowercase(Locale.ROOT)
    if (format == "csv") return true
    val accept = request.headers[HttpHeaders.Accept] ?: return false
    return ACCEPT_CSV.containsMatchIn(accept)
}

private fun PortfolioMetricsReport.toCsv(): String {
    val header =
        when (period) {
            MetricsPeriod.DAILY -> "date,valuation,cashflow,pnlDaily,pnlTotal"
            MetricsPeriod.WEEKLY, MetricsPeriod.MONTHLY -> "periodStart,periodEnd,valuation,cashflow,pnlDaily,pnlTotal"
        }
    val rows =
        series.joinToString(separator = "\n") { point ->
            when (period) {
                MetricsPeriod.DAILY ->
                    listOf(
                        point.date?.toString().orEmpty(),
                        point.valuation.toAmt(),
                        point.cashflow.toAmt(),
                        point.pnlDaily.toAmt(),
                        point.pnlTotal.toAmt(),
                    ).joinToString(",")
                MetricsPeriod.WEEKLY, MetricsPeriod.MONTHLY ->
                    listOf(
                        point.periodStart?.toString().orEmpty(),
                        point.periodEnd?.toString().orEmpty(),
                        point.valuation.toAmt(),
                        point.cashflow.toAmt(),
                        point.pnlDaily.toAmt(),
                        point.pnlTotal.toAmt(),
                    ).joinToString(",")
            }
        }
    return if (rows.isEmpty()) header else "$header\n$rows"
}

private fun BigDecimal.toAmt(): String = setScale(AMT_SCALE, java.math.RoundingMode.HALF_UP).toPlainString()

private const val AMT_SCALE = 8

private val ALLOWED_BASES = setOf("RUB", "USD")
