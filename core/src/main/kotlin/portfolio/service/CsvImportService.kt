package portfolio.service

import java.io.Reader
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.ConsistentCopyVisibility
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.Money
import portfolio.model.TradeSide
import portfolio.model.TradeView
import portfolio.model.ValuationMethod

class CsvImportService(
    private val instrumentResolver: InstrumentResolver,
    private val tradeLookup: TradeLookup,
    private val portfolioService: PortfolioService,
) {
    suspend fun import(
        portfolioId: UUID,
        reader: Reader,
        valuationMethod: ValuationMethod,
    ): DomainResult<ImportReport> = withContext(Dispatchers.IO) {
        reader.buffered().use { buffered ->
            val headerLine = buffered.readLine() ?: return@use DomainResult.success(ImportReport())
            val header = parseHeader(headerLine)
                ?: return@use failure(
                    PortfolioError.Validation("CSV header must contain: ${EXPECTED_HEADER.joinToString(",")}"),
                )

            if (header != EXPECTED_HEADER) {
                val actual = header.joinToString(",")
                return@use failure(
                    PortfolioError.Validation("Unexpected CSV header: $actual"),
                )
            }

            var lineNumber = 1
            var inserted = 0
            var duplicates = 0
            val failures = mutableListOf<ImportFailure>()
            val seenExtIds = mutableSetOf<String>()
            val seenSoftKeys = mutableSetOf<SoftTradeKey>()

            for (line in buffered.lineSequence()) {
                lineNumber += 1
                if (line.isBlank()) continue

                val columns = try {
                    parseLine(line)
                } catch (iae: IllegalArgumentException) {
                    failures += ImportFailure(lineNumber, null, iae.message ?: "Invalid CSV row")
                    continue
                }

                if (columns.size != header.size) {
                    failures += ImportFailure(
                        lineNumber,
                        null,
                        "Expected ${header.size} columns but found ${columns.size}",
                    )
                    continue
                }

                val rowValues = header.indices.associate { index ->
                    header[index] to columns[index]
                }

                val parsed = parseRow(rowValues)
                when (parsed) {
                    is RowParseResult.Failure -> {
                        failures += ImportFailure(lineNumber, parsed.extId, parsed.message)
                    }
                    is RowParseResult.Success -> {
                        val row = parsed.value

                        val instrumentResult = resolveInstrument(row)
                        if (instrumentResult.isFailure) {
                            failures += ImportFailure(
                                lineNumber,
                                row.extId,
                                errorMessage(instrumentResult.exceptionOrNull()!!),
                            )
                            continue
                        }

                        val instrument = instrumentResult.getOrNull()
                        if (instrument == null) {
                            failures += ImportFailure(
                                lineNumber,
                                row.extId,
                                "Instrument not found for ${row.ticker}",
                            )
                            continue
                        }

                        val extId = row.extId
                        if (extId != null && seenExtIds.contains(extId)) {
                            duplicates += 1
                            continue
                        }

                        val softKey = SoftTradeKey.of(
                            portfolioId = portfolioId,
                            instrumentId = instrument.instrumentId,
                            executedAt = row.executedAt,
                            side = row.side,
                            quantity = row.quantity,
                            price = row.price,
                        )

                        if (seenSoftKeys.contains(softKey)) {
                            duplicates += 1
                            continue
                        }

                        if (extId != null) {
                            val existsByExtId = tradeLookup.existsByExternalId(portfolioId, extId)
                            if (existsByExtId.isFailure) {
                                failures += ImportFailure(
                                    lineNumber,
                                    extId,
                                    errorMessage(existsByExtId.exceptionOrNull()!!),
                                )
                                continue
                            }
                            if (existsByExtId.getOrThrow()) {
                                seenExtIds += extId
                                seenSoftKeys += softKey
                                duplicates += 1
                                continue
                            }
                        }

                        val existsByKey = tradeLookup.existsBySoftKey(softKey)
                        if (existsByKey.isFailure) {
                            failures += ImportFailure(
                                lineNumber,
                                extId,
                                errorMessage(existsByKey.exceptionOrNull()!!),
                            )
                            continue
                        }
                        if (existsByKey.getOrThrow()) {
                            extId?.let { seenExtIds += it }
                            seenSoftKeys += softKey
                            duplicates += 1
                            continue
                        }

                        val tradeView = buildTradeView(
                            row = row,
                            portfolioId = portfolioId,
                            instrumentId = instrument.instrumentId,
                        )

                        val applied = portfolioService.applyTrade(tradeView, valuationMethod)
                        if (applied.isSuccess) {
                            inserted += 1
                            seenSoftKeys += softKey
                            extId?.let { seenExtIds += it }
                        } else {
                            failures += ImportFailure(
                                lineNumber,
                                extId,
                                errorMessage(applied.exceptionOrNull()!!),
                            )
                        }
                    }
                }
            }

            DomainResult.success(
                ImportReport(
                    inserted = inserted,
                    skippedDuplicates = duplicates,
                    failed = failures,
                ),
            )
        }
    }

    private fun buildTradeView(
        row: ParsedTrade,
        portfolioId: UUID,
        instrumentId: Long,
    ): TradeView {
        val priceMoney = Money.of(row.price, row.currency)
        val feeMoney = Money.of(row.fee, row.feeCurrency)
        val taxMoney = row.tax?.let { Money.of(it, row.taxCurrency ?: row.currency) }
        val tradeDate = LocalDate.ofInstant(row.executedAt, ZoneOffset.UTC)
        return TradeView(
            tradeId = UUID.randomUUID(),
            portfolioId = portfolioId,
            instrumentId = instrumentId,
            tradeDate = tradeDate,
            side = row.side,
            quantity = row.quantity,
            price = priceMoney,
            fee = feeMoney,
            tax = taxMoney,
            broker = row.broker,
            note = row.note,
            externalId = row.extId,
            executedAt = row.executedAt,
        )
    }

    private suspend fun resolveInstrument(row: ParsedTrade): DomainResult<InstrumentRef?> {
        row.exchange?.let { exchange ->
            val result = instrumentResolver.findBySymbol(exchange, row.board, row.ticker)
            if (result.isFailure) return result
            val instrument = result.getOrNull()
            if (instrument != null) {
                return DomainResult.success(instrument)
            }
        }

        row.aliasSource?.let { source ->
            val result = instrumentResolver.findByAlias(row.ticker, source)
            if (result.isFailure) return result
            val instrument = result.getOrNull()
            if (instrument != null) {
                return DomainResult.success(instrument)
            }
        }

        return DomainResult.success(null)
    }

    private fun parseHeader(line: String): List<String>? {
        val values = try {
            parseLine(line)
        } catch (_: IllegalArgumentException) {
            return null
        }

        if (values.isEmpty()) return null

        return values.mapIndexed { index, raw ->
            val trimmed = raw.trim()
            val withoutBom = if (index == 0) trimmed.removePrefix("\uFEFF") else trimmed
            withoutBom.lowercase(Locale.ROOT)
        }
    }

    private fun parseRow(values: Map<String, String>): RowParseResult {
        val extId = values["ext_id"].orEmpty().ifBlank { null }

        val executedAt = parseInstant(values["datetime"]) ?: return RowParseResult.Failure(
            extId,
            "Invalid or missing datetime",
        )

        val ticker = values["ticker"].orEmpty().trim().uppercase(Locale.ROOT)
        if (ticker.isEmpty()) {
            return RowParseResult.Failure(extId, "Ticker is required")
        }

        val exchange = values["exchange"].orEmpty().trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
        val board = values["board"].orEmpty().trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
        val aliasSource = values["alias_source"].orEmpty().trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)

        val sideRaw = values["side"].orEmpty().trim().uppercase(Locale.ROOT)
        val side = try {
            TradeSide.valueOf(sideRaw)
        } catch (_: IllegalArgumentException) {
            return RowParseResult.Failure(extId, "Unknown trade side: $sideRaw")
        }

        val quantity = parseDecimal(values["quantity"], positive = true)
            ?: return RowParseResult.Failure(extId, "Quantity must be a positive decimal")

        val price = parseDecimal(values["price"], positive = true)
            ?: return RowParseResult.Failure(extId, "Price must be a positive decimal")

        val currency = parseCurrency(values["currency"], default = null)
            ?: return RowParseResult.Failure(extId, "Currency is required")

        val fee = parseDecimal(values["fee"], positive = false) ?: BigDecimal.ZERO
        if (fee < BigDecimal.ZERO) {
            return RowParseResult.Failure(extId, "Fee cannot be negative")
        }

        val feeCurrency = parseCurrency(values["fee_currency"], default = currency)
            ?: return RowParseResult.Failure(extId, "Fee currency is invalid")

        val taxRaw = values["tax"]
        val tax = parseDecimal(taxRaw, positive = false)
        if (tax != null && tax < BigDecimal.ZERO) {
            return RowParseResult.Failure(extId, "Tax cannot be negative")
        }

        val taxCurrency = if (tax != null) {
            parseCurrency(values["tax_currency"], default = currency)
                ?: return RowParseResult.Failure(extId, "Tax currency is invalid")
        } else {
            values["tax_currency"].orEmpty().trim().takeIf { it.isNotEmpty() }?.let {
                parseCurrency(it, default = null)
                    ?: return RowParseResult.Failure(extId, "Tax currency requires a tax amount")
            }
        }

        val broker = values["broker"].orEmpty().trim().takeIf { it.isNotEmpty() }
        val note = values["note"].orEmpty().trim().takeIf { it.isNotEmpty() }

        if (exchange == null && aliasSource == null) {
            return RowParseResult.Failure(extId, "Either exchange or alias_source must be provided for $ticker")
        }

        return RowParseResult.Success(
            ParsedTrade(
                extId = extId,
                executedAt = executedAt,
                ticker = ticker,
                exchange = exchange,
                board = board,
                aliasSource = aliasSource,
                side = side,
                quantity = quantity,
                price = price,
                currency = currency,
                fee = fee,
                feeCurrency = feeCurrency,
                tax = tax,
                taxCurrency = taxCurrency,
                broker = broker,
                note = note,
            ),
        )
    }

    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseDecimal(raw: String?, positive: Boolean): BigDecimal? {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return null
        val decimal = value.toBigDecimalOrNull() ?: return null
        return if (positive && decimal <= BigDecimal.ZERO) {
            null
        } else {
            normalizeDecimal(decimal)
        }
    }

    private fun parseCurrency(raw: String?, default: String?): String? {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) {
            return default?.uppercase(Locale.ROOT)
        }
        val normalized = value.uppercase(Locale.ROOT)
        return if (CURRENCY_REGEX.matches(normalized)) normalized else null
    }

    private fun parseLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' -> {
                    if (inQuotes && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                char == ',' && !inQuotes -> {
                    result += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index += 1
        }
        if (inQuotes) {
            throw IllegalArgumentException("Unterminated quote in CSV row")
        }
        result += current.toString()
        return result
    }

    private fun normalizeDecimal(value: BigDecimal): BigDecimal {
        val stripped = value.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    private fun errorMessage(throwable: Throwable): String {
        val portfolioError = (throwable as? PortfolioException)?.error
        return portfolioError?.message ?: throwable.message ?: "Unknown error"
    }

    private fun failure(error: PortfolioError): DomainResult<Nothing> =
        DomainResult.failure(PortfolioException(error))

    private sealed interface RowParseResult {
        data class Success(val value: ParsedTrade) : RowParseResult
        data class Failure(val extId: String?, val message: String) : RowParseResult
    }

    data class ImportReport(
        val inserted: Int = 0,
        val skippedDuplicates: Int = 0,
        val failed: List<ImportFailure> = emptyList(),
    )

    data class ImportFailure(
        val lineNumber: Int,
        val extId: String?,
        val message: String,
    )

    @ConsistentCopyVisibility
    data class SoftTradeKey private constructor(
        val portfolioId: UUID,
        val instrumentId: Long,
        val executedAt: Instant,
        val side: TradeSide,
        val quantity: BigDecimal,
        val price: BigDecimal,
    ) {
        companion object {
            fun of(
                portfolioId: UUID,
                instrumentId: Long,
                executedAt: Instant,
                side: TradeSide,
                quantity: BigDecimal,
                price: BigDecimal,
            ): SoftTradeKey = SoftTradeKey(
                portfolioId = portfolioId,
                instrumentId = instrumentId,
                executedAt = executedAt,
                side = side,
                quantity = normalize(quantity),
                price = normalize(price),
            )

            private fun normalize(value: BigDecimal): BigDecimal {
                val stripped = value.stripTrailingZeros()
                return if (stripped.scale() < 0) stripped.setScale(0) else stripped
            }
        }
    }

    data class ParsedTrade(
        val extId: String?,
        val executedAt: Instant,
        val ticker: String,
        val exchange: String?,
        val board: String?,
        val aliasSource: String?,
        val side: TradeSide,
        val quantity: BigDecimal,
        val price: BigDecimal,
        val currency: String,
        val fee: BigDecimal,
        val feeCurrency: String,
        val tax: BigDecimal?,
        val taxCurrency: String?,
        val broker: String?,
        val note: String?,
    )

    interface InstrumentResolver {
        suspend fun findBySymbol(
            exchange: String,
            board: String?,
            symbol: String,
        ): DomainResult<InstrumentRef?>

        suspend fun findByAlias(
            alias: String,
            source: String,
        ): DomainResult<InstrumentRef?>
    }

    interface TradeLookup {
        suspend fun existsByExternalId(portfolioId: UUID, externalId: String): DomainResult<Boolean>
        suspend fun existsBySoftKey(key: SoftTradeKey): DomainResult<Boolean>
    }

    data class InstrumentRef(val instrumentId: Long)

    companion object {
        private val EXPECTED_HEADER = listOf(
            "ext_id",
            "datetime",
            "ticker",
            "exchange",
            "board",
            "alias_source",
            "side",
            "quantity",
            "price",
            "currency",
            "fee",
            "fee_currency",
            "tax",
            "tax_currency",
            "broker",
            "note",
        )

        private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")
    }
}
