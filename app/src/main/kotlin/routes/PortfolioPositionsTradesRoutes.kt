package routes

import common.runCatchingNonFatal
import db.DatabaseFactory
import di.portfolioModule
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import model.TradeDto
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import portfolio.model.Money
import portfolio.model.PositionView
import repo.mapper.toTradeDto
import repo.tables.TradesTable
import routes.dto.MoneyDto
import routes.dto.PositionItemResponse
import routes.dto.TradeItemResponse
import routes.dto.TradesPageResponse
import routes.dto.paramDate
import routes.dto.paramLimit
import routes.dto.paramOffset
import routes.dto.paramOrder
import routes.dto.paramSide
import routes.dto.paramSort
import security.userIdOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

fun Route.portfolioPositionsTradesRoutes() {
    route("/api/portfolio/{id}") {
        get("/positions") {
            val subject = call.userIdOrNull
            if (subject == null) {
                call.respondUnauthorized()
                return@get
            }

            val portfolioId = call.requirePortfolioId() ?: return@get
            val sort =
                runCatchingNonFatal { call.paramSort() }.getOrElse {
                    call.respondBadRequest(listOf(it.message ?: "invalid_request"))
                    return@get
                }
            val order =
                runCatchingNonFatal { call.paramOrder() }.getOrElse {
                    call.respondBadRequest(listOf(it.message ?: "invalid_request"))
                    return@get
                }

            val deps = call.positionsTradesDeps()
            deps.listPositions(portfolioId).fold(
                onSuccess = { positions ->
                    val sorted = positions.sorted(sort, order)
                    val response = sorted.map { it.toResponse() }
                    call.respond(response)
                },
                onFailure = { error -> call.handleDomainError(error) },
            )
        }

        get("/trades") {
            val subject = call.userIdOrNull
            if (subject == null) {
                call.respondUnauthorized()
                return@get
            }

            val portfolioId = call.requirePortfolioId() ?: return@get
            val limit =
                runCatchingNonFatal { call.paramLimit() }.getOrElse {
                    call.respondBadRequest(listOf(it.message ?: "invalid_request"))
                    return@get
                }
            val offset =
                runCatchingNonFatal { call.paramOffset() }.getOrElse {
                    call.respondBadRequest(listOf(it.message ?: "invalid_request"))
                    return@get
                }
            val from =
                runCatchingNonFatal { call.paramDate("from") }.getOrElse {
                    call.respondBadRequest(listOf(it.message ?: "invalid_request"))
                    return@get
                }
            val to =
                runCatchingNonFatal { call.paramDate("to") }.getOrElse {
                    call.respondBadRequest(listOf(it.message ?: "invalid_request"))
                    return@get
                }
            val side =
                runCatchingNonFatal { call.paramSide() }.getOrElse {
                    call.respondBadRequest(listOf(it.message ?: "invalid_request"))
                    return@get
                }

            if (from != null && to != null && to.isBefore(from)) {
                call.respondBadRequest(listOf("from must be on or before to"))
                return@get
            }

            val deps = call.positionsTradesDeps()
            val query =
                TradesQuery(
                    limit = limit,
                    offset = offset,
                    from = from,
                    to = to,
                    side = side,
                )
            deps.listTrades(portfolioId, query).fold(
                onSuccess = { page ->
                    val response =
                        TradesPageResponse(
                            total = page.total,
                            items = page.items.map { it.toResponse() },
                            limit = limit,
                            offset = offset,
                        )
                    call.respond(response)
                },
                onFailure = { error -> call.handleDomainError(error) },
            )
        }
    }
}

internal val PortfolioPositionsTradesDepsKey =
    AttributeKey<PortfolioPositionsTradesDeps>(
        "PortfolioPositionsTradesDeps",
    )

internal data class PortfolioPositionsTradesDeps(
    val listPositions: suspend (UUID) -> Result<List<PositionView>>,
    val listTrades: suspend (UUID, TradesQuery) -> Result<TradesData>,
)

internal data class TradesQuery(
    val limit: Int,
    val offset: Int,
    val from: LocalDate?,
    val to: LocalDate?,
    val side: String?,
)

internal data class TradesData(
    val total: Long,
    val items: List<TradeRecord>,
)

internal data class TradeRecord(
    val instrumentId: Long,
    val tradeDate: LocalDate,
    val side: String,
    val quantity: BigDecimal,
    val price: Money,
    val notional: Money,
    val fee: Money,
    val tax: Money?,
    val extId: String?,
)

private fun ApplicationCall.positionsTradesDeps(): PortfolioPositionsTradesDeps {
    val attributes = application.attributes
    if (attributes.contains(PortfolioPositionsTradesDepsKey)) {
        return attributes[PortfolioPositionsTradesDepsKey]
    }
    val deps = buildPositionsTradesDeps()
    attributes.put(PortfolioPositionsTradesDepsKey, deps)
    return deps
}

private fun ApplicationCall.buildPositionsTradesDeps(): PortfolioPositionsTradesDeps {
    val module = application.portfolioModule()
    val services = module.services
    return PortfolioPositionsTradesDeps(
        listPositions = { portfolioId ->
            services.portfolioService.listPositions(
                portfolioId = portfolioId,
                on = LocalDate.now(ZoneOffset.UTC),
                pricingService = services.pricingService,
                fxRateService = services.fxRateService,
            )
        },
        listTrades = { portfolioId, query -> fetchTrades(portfolioId, query) },
    )
}

private suspend fun fetchTrades(
    portfolioId: UUID,
    query: TradesQuery,
): Result<TradesData> =
    runCatchingNonFatal {
        DatabaseFactory.dbQuery {
            val condition = buildTradeCondition(portfolioId, query)
            val total = TradesTable.select { condition }.count()
            val rows =
                TradesTable
                    .select { condition }
                    .orderBy(TradesTable.datetime, SortOrder.DESC)
                    .orderBy(TradesTable.instrumentId, SortOrder.ASC)
                    .limit(query.limit, query.offset.toLong())
                    .map { it.toTradeDto() }
            TradesData(
                total = total,
                items = rows.map { it.toTradeRecord() },
            )
        }
    }

private fun buildTradeCondition(
    portfolioId: UUID,
    query: TradesQuery,
): Op<Boolean> {
    var condition: Op<Boolean> = TradesTable.portfolioId eq portfolioId
    query.from?.let {
        condition = condition and (TradesTable.datetime greaterEq it.atStartOfDayUtc())
    }
    query.to?.let {
        condition = condition and (TradesTable.datetime less it.plusDays(1).atStartOfDayUtc())
    }
    query.side?.let {
        condition = condition and (TradesTable.side eq it)
    }
    return condition
}

private fun LocalDate.atStartOfDayUtc(): OffsetDateTime = atStartOfDay().atOffset(ZoneOffset.UTC)

private suspend fun ApplicationCall.requirePortfolioId(): UUID? {
    val raw = parameters["id"]?.trim()
    if (raw.isNullOrEmpty()) {
        respondBadRequest(listOf("portfolioId invalid"))
        return null
    }
    return runCatchingNonFatal { UUID.fromString(raw) }.getOrElse {
        respondBadRequest(listOf("portfolioId invalid"))
        null
    }
}

private fun List<PositionView>.sorted(
    sortKey: String,
    order: String,
): List<PositionView> {
    val comparator =
        when (sortKey) {
            "qty" -> compareBy<PositionView> { it.quantity }
            "upl" -> compareBy { it.unrealizedPnl.amount }
            else -> compareBy { it.instrumentId }
        }
    return if (order == "desc") sortedWith(comparator.reversed()) else sortedWith(comparator)
}

private fun PositionView.toResponse(): PositionItemResponse {
    val quantityValue = quantity
    val averageCostMoney = averageCost ?: Money.zero(valuation.currency)
    val lastPriceMoney =
        if (quantityValue.compareTo(BigDecimal.ZERO) == 0) {
            Money.zero(valuation.currency)
        } else {
            val priceAmount = valuation.amount.divide(quantityValue, SCALE, RoundingMode.HALF_UP)
            Money.of(priceAmount, valuation.currency)
        }
    return PositionItemResponse(
        instrumentId = instrumentId,
        qty = quantityValue.toAmountString(),
        avgPrice = averageCostMoney.toDto(),
        lastPrice = lastPriceMoney.toDto(),
        upl = unrealizedPnl.toDto(),
    )
}

private fun TradeRecord.toResponse(): TradeItemResponse =
    TradeItemResponse(
        instrumentId = instrumentId,
        tradeDate = tradeDate.toString(),
        side = side,
        quantity = quantity.toAmountString(),
        price = price.toDto(),
        notional = notional.toDto(),
        fee = fee.toDto(),
        tax = tax?.toDto(),
        extId = extId,
    )

private fun TradeDto.toTradeRecord(): TradeRecord {
    val priceMoney = Money.of(price, priceCurrency)
    val feeMoney = Money.of(fee, feeCurrency)
    val taxMoney =
        tax?.let { value ->
            val currency = taxCurrency ?: priceCurrency
            Money.of(value, currency)
        }
    val tradeDate = datetime.atOffset(ZoneOffset.UTC).toLocalDate()
    return TradeRecord(
        instrumentId = instrumentId,
        tradeDate = tradeDate,
        side = side.uppercase(),
        quantity = quantity,
        price = priceMoney,
        notional = priceMoney * quantity,
        fee = feeMoney,
        tax = taxMoney,
        extId = extId,
    )
}

private fun Money.toDto(): MoneyDto =
    MoneyDto(
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(),
        ccy = currency,
    )

private fun BigDecimal.toAmountString(): String = setScale(SCALE, RoundingMode.HALF_UP).toPlainString()

private const val SCALE = 8
