package routes

import db.DatabaseFactory.dbQuery
import di.portfolioModule
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.configOrNull
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import portfolio.errors.DomainResult
import portfolio.service.CsvImportService
import repo.InstrumentRepository
import repo.TradeRepository
import repo.tables.TradesTable
import routes.dto.ImportFailedItem
import routes.dto.ImportReportResponse
import security.userIdOrNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

private const val MAX_LINES = 100_000
private const val MAX_LINE_LENGTH = 64_000

fun Route.portfolioImportRoutes() {
    post("/api/portfolio/{id}/trades/import/csv") {
        val subject = call.userIdOrNull
        if (subject == null) {
            call.respondUnauthorized()
            return@post
        }

        val portfolioId = call.parameters["id"].toPortfolioIdOrNull()
        if (portfolioId == null) {
            call.respondBadRequest(listOf("portfolioId invalid"))
            return@post
        }

        val deps = call.importDeps()
        val uploadSettings = deps.uploadSettings
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > uploadSettings.csvMaxBytes) {
            call.respondPayloadTooLarge(uploadSettings.csvMaxBytes)
            return@post
        }

        val requestContentType = runCatching { call.request.contentType() }.getOrNull()
        if (requestContentType == null || !requestContentType.match(ContentType.MultiPart.FormData)) {
            call.respondBadRequest(listOf("multipart form-data required"))
            return@post
        }

        val multipart = call.receiveMultipart()
        var processed = false
        while (true) {
            val part = multipart.readPart() ?: break
            when (part) {
                is PartData.FileItem -> {
                    val name = part.name?.lowercase()
                    if (name != null && name != "file") {
                        part.dispose()
                        continue
                    }
                    processed = true
                    val contentType = part.contentType
                    if (contentType == null || !uploadSettings.isAllowed(contentType)) {
                        part.dispose()
                        call.respondUnsupportedMediaType()
                        return@post
                    }
                    val declaredLength = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (declaredLength != null && declaredLength > uploadSettings.csvMaxBytes) {
                        part.dispose()
                        call.respondPayloadTooLarge(uploadSettings.csvMaxBytes)
                        return@post
                    }

                    val importResult: DomainResult<CsvImportService.ImportReport>
                    try {
                        importResult = part.provider().toInputStream().use { stream ->
                            val reader = stream.toUtf8Reader()
                            LineLimitingReader(reader, MAX_LINES, MAX_LINE_LENGTH).use { limitingReader ->
                                deps.importCsv(portfolioId, limitingReader)
                            }
                        }
                    } catch (limit: LineLimitExceededException) {
                        call.respondPayloadTooLarge(uploadSettings.csvMaxBytes)
                        return@post
                    } catch (coding: CharacterCodingException) {
                        call.respondBadRequest(listOf("file must be UTF-8 encoded"))
                        return@post
                    } catch (cause: Throwable) {
                        call.application.environment.log.error("CSV import failed", cause)
                        call.respondInternal()
                        return@post
                    } finally {
                        part.dispose()
                    }

                    importResult.fold(
                        onSuccess = { report ->
                            val response = ImportReportResponse(
                                inserted = report.inserted,
                                skippedDuplicates = report.skippedDuplicates,
                                failed = report.failed.map { ImportFailedItem(line = it.lineNumber, error = it.message) },
                            )
                            call.respond(response)
                        },
                        onFailure = { error -> call.handleDomainError(error) },
                    )
                    return@post
                }

                else -> part.dispose()
            }
        }

        if (!processed) {
            call.respondBadRequest(listOf("file part is required"))
            return@post
        }

        call.respondInternal()
    }
}

private fun String?.toPortfolioIdOrNull(): UUID? = this?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
    runCatching { UUID.fromString(value) }.getOrNull()
}

internal val PortfolioImportDepsKey = AttributeKey<PortfolioImportDeps>("PortfolioImportDeps")

internal data class PortfolioImportDeps(
    val importCsv: suspend (UUID, Reader) -> DomainResult<CsvImportService.ImportReport>,
    val uploadSettings: UploadSettings,
)

private fun ApplicationCall.importDeps(): PortfolioImportDeps {
    val attributes = application.attributes
    if (attributes.contains(PortfolioImportDepsKey)) {
        return attributes[PortfolioImportDepsKey]
    }

    val module = application.portfolioModule()
    val instrumentRepository = module.repositories.instrumentRepository
    val tradeRepository = module.repositories.tradeRepository
    val portfolioService = module.services.portfolioService
    val valuationMethod = module.settings.portfolio.defaultValuationMethod
    val settings = UploadSettings.fromConfig(application.environment.config)
    val service = CsvImportService(
        instrumentResolver = DatabaseInstrumentResolver(instrumentRepository),
        tradeLookup = DatabaseTradeLookup(tradeRepository),
        portfolioService = portfolioService,
    )
    val deps = PortfolioImportDeps(
        importCsv = { portfolioId, reader -> service.import(portfolioId, reader, valuationMethod) },
        uploadSettings = settings,
    )
    attributes.put(PortfolioImportDepsKey, deps)
    return deps
}

internal data class UploadSettings(
    val csvMaxBytes: Long,
    val allowedContentTypes: Set<ContentType>,
) {
    fun isAllowed(contentType: ContentType): Boolean = allowedContentTypes.any { allowed -> contentType.match(allowed) }

    companion object {
        private const val DEFAULT_MAX_BYTES = 1_048_576L
        private val DEFAULT_ALLOWED = setOf(
            ContentType.Text.CSV,
            ContentType.Application.OctetStream,
            ContentType.parse("application/vnd.ms-excel"),
        )

        fun fromConfig(config: ApplicationConfig): UploadSettings {
            val uploadConfig = config.configOrNull("upload")
            val maxBytes = uploadConfig?.longProperty("csvMaxBytes") ?: DEFAULT_MAX_BYTES
            val allowed = uploadConfig?.contentTypeSet("allowedCsvContentTypes") ?: DEFAULT_ALLOWED
            return UploadSettings(csvMaxBytes = maxBytes, allowedContentTypes = allowed)
        }
    }
}

private fun ApplicationConfig.longProperty(name: String): Long? {
    val raw = propertyOrNull(name)?.getString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val value = raw.toLongOrNull() ?: throw IllegalArgumentException("upload.$name must be a positive integer")
    require(value > 0) { "upload.$name must be greater than zero" }
    return value
}

private fun ApplicationConfig.contentTypeSet(name: String): Set<ContentType>? {
    val values = propertyOrNull(name)?.getList()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
    if (values.isEmpty()) return null
    return values.map { ContentType.parse(it) }.toSet()
}

private fun java.io.InputStream.toUtf8Reader(): Reader {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return InputStreamReader(this, decoder)
}

private class LineLimitingReader(
    private val delegate: Reader,
    private val maxLines: Int,
    private val maxLineLength: Int,
) : Reader() {
    private var lineCount = 0
    private var currentLineLength = 0
    private var lastWasCarriageReturn = false
    private var sawAnyCharacter = false

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val read = delegate.read(cbuf, off, len)
        if (read <= 0) {
            return read
        }
        for (index in 0 until read) {
            val char = cbuf[off + index]
            when (char) {
                '\n' -> {
                    if (!lastWasCarriageReturn) {
                        incrementLines()
                    }
                    currentLineLength = 0
                    lastWasCarriageReturn = false
                }
                '\r' -> {
                    incrementLines()
                    currentLineLength = 0
                    lastWasCarriageReturn = true
                }
                else -> {
                    sawAnyCharacter = true
                    currentLineLength += 1
                    if (currentLineLength > maxLineLength) {
                        throw LineLimitExceededException()
                    }
                    lastWasCarriageReturn = false
                }
            }
        }
        return read
    }

    override fun close() {
        val effectiveLines = when {
            !sawAnyCharacter && lineCount == 0 -> 0
            currentLineLength > 0 -> lineCount + 1
            lastWasCarriageReturn -> lineCount
            else -> lineCount
        }
        if (effectiveLines > maxLines) {
            throw LineLimitExceededException()
        }
        delegate.close()
    }

    private fun incrementLines() {
        lineCount += 1
        if (lineCount > maxLines) {
            throw LineLimitExceededException()
        }
    }
}

private class LineLimitExceededException : RuntimeException()

private class DatabaseInstrumentResolver(
    private val repository: InstrumentRepository,
) : CsvImportService.InstrumentResolver {
    override suspend fun findBySymbol(
        exchange: String,
        board: String?,
        symbol: String,
    ): DomainResult<CsvImportService.InstrumentRef?> = runCatching {
        repository.findBySymbol(exchange, board, symbol)?.let { CsvImportService.InstrumentRef(it.instrumentId) }
    }

    override suspend fun findByAlias(
        alias: String,
        source: String,
    ): DomainResult<CsvImportService.InstrumentRef?> = runCatching {
        repository.findAlias(alias, source)?.let { aliasEntity ->
            repository.findById(aliasEntity.instrumentId)?.let { CsvImportService.InstrumentRef(it.instrumentId) }
        }
    }
}

private class DatabaseTradeLookup(
    private val repository: TradeRepository,
) : CsvImportService.TradeLookup {
    override suspend fun existsByExternalId(
        portfolioId: UUID,
        externalId: String,
    ): DomainResult<Boolean> = runCatching {
        repository.findByExternalId(externalId)?.portfolioId == portfolioId
    }

    override suspend fun existsBySoftKey(key: CsvImportService.SoftTradeKey): DomainResult<Boolean> = runCatching {
        dbQuery {
            TradesTable
                .select {
                    (TradesTable.portfolioId eq key.portfolioId) and
                        (TradesTable.instrumentId eq key.instrumentId) and
                        (TradesTable.datetime eq key.executedAt.toUtcTimestamp()) and
                        (TradesTable.side eq key.side.name) and
                        (TradesTable.quantity eq key.quantity) and
                        (TradesTable.price eq key.price)
                }
                .limit(1)
                .any()
        }
    }
}

private fun Instant.toUtcTimestamp(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
