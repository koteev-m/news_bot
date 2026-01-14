package routes

import db.DatabaseFactory.dbQuery
import di.portfolioModule
import integrations.integrationsModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.configOrNull
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import io.ktor.utils.io.cancel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import portfolio.errors.DomainResult
import portfolio.service.CsvImportService
import repo.InstrumentRepository
import repo.TradeRepository
import repo.tables.TradesTable
import routes.dto.ImportByUrlRequest
import routes.dto.ImportFailedItem
import routes.dto.ImportReportResponse
import routes.respondServiceUnavailable
import routes.respondTooManyRequests
import security.RateLimitConfig
import security.RateLimiter
import security.userIdOrNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.slf4j.Logger
import common.runCatchingNonFatal

private const val MAX_LINES = 100_000
private const val MAX_LINE_LENGTH = 64_000
private const val SNIFF_PREVIEW_LIMIT = 2_048
private const val CSV_ACCEPT_HEADER = "text/csv, text/plain; q=0.8, */*; q=0.1"

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
        val multipart = try {
            call.receiveMultipart()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (cause: Throwable) {
            call.application.environment.log.error("csv_multipart_error", cause)
            call.respondInternal()
            return@post
        }

        var processed = false
        while (true) {
            val part = multipart.readPart() ?: break
            when (part) {
                is io.ktor.http.content.PartData.FileItem -> {
                    val partName = part.name?.lowercase()
                    if (partName != null && partName != "file") {
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
                        part.dispose()
                        return@post
                    } catch (coding: CharacterCodingException) {
                        part.dispose()
                        call.respondBadRequest(listOf("file must be UTF-8 encoded"))
                        return@post
                    } catch (cancellation: CancellationException) {
                        part.dispose()
                        throw cancellation
                    } catch (err: Error) {
                        part.dispose()
                        throw err
                    } catch (cause: Throwable) {
                        part.dispose()
                        call.application.environment.log.error("csv_import_failed", cause)
                        call.respondInternal()
                        return@post
                    }

                    part.dispose()
                    call.respondImportResult(importResult)
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

    post("/api/portfolio/{id}/trades/import/by-url") {
        val userId = call.userIdOrNull
        if (userId == null) {
            call.respondUnauthorized()
            return@post
        }

        val subject = call.rateLimitSubject(userId)

        val settings = call.importByUrlSettings()
        if (!settings.enabled) {
            call.respondServiceUnavailable()
            return@post
        }
        val limiter = call.application.importByUrlRateLimiter(settings.rateLimit)
        val (allowed, retryAfter) = limiter.tryAcquire(subject)
        if (!allowed) {
            call.respondTooManyRequests(retryAfter ?: 60)
            return@post
        }

        val portfolioId = call.parameters["id"].toPortfolioIdOrNull()
        if (portfolioId == null) {
            call.respondBadRequest(listOf("portfolioId invalid"))
            return@post
        }

        val request = try {
            call.receive<ImportByUrlRequest>()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (_: Throwable) {
            call.respondBadRequest(listOf("invalid json"))
            return@post
        }

        val rawUrl = request.url.trim()
        if (rawUrl.isEmpty()) {
            call.respondBadRequest(listOf("url must not be empty"))
            return@post
        }
        val httpsUrl = runCatchingNonFatal { java.net.URI(rawUrl) }.getOrNull()
        if (httpsUrl == null || httpsUrl.scheme == null || httpsUrl.scheme.lowercase() != "https") {
            call.respondBadRequest(listOf("url must use https"))
            return@post
        }

        val deps = call.importDeps()
        val uploadSettings = deps.uploadSettings
        val remoteCsv = try {
            deps.downloadCsv(rawUrl, uploadSettings.csvMaxBytes)
        } catch (tooLarge: RemoteCsvTooLargeException) {
            call.respondPayloadTooLarge(tooLarge.limit)
            return@post
        } catch (download: RemoteCsvDownloadException) {
            call.application.environment.log.warn("csv_download_failed: {}", download.code)
            call.respondInternal()
            return@post
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (cause: Throwable) {
            call.application.environment.log.error("csv_download_error", cause)
            call.respondInternal()
            return@post
        }

        val contentType = remoteCsv.contentType
        val isProbablyCsv = looksLikeCsv(remoteCsv.bytes)
        if (contentType != null && !uploadSettings.isAllowed(contentType) && !isProbablyCsv) {
            call.respondUnsupportedMediaType()
            return@post
        }
        if (contentType == null && !isProbablyCsv) {
            call.respondUnsupportedMediaType()
            return@post
        }

        val importResult: DomainResult<CsvImportService.ImportReport>
        try {
            ByteArrayInputStream(remoteCsv.bytes).use { stream ->
                val reader = stream.toUtf8Reader()
                LineLimitingReader(reader, MAX_LINES, MAX_LINE_LENGTH).use { limitingReader ->
                    importResult = deps.importCsv(portfolioId, limitingReader)
                }
            }
        } catch (limit: LineLimitExceededException) {
            call.respondPayloadTooLarge(uploadSettings.csvMaxBytes)
            return@post
        } catch (coding: CharacterCodingException) {
            call.respondBadRequest(listOf("file must be UTF-8 encoded"))
            return@post
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (cause: Throwable) {
            call.application.environment.log.error("csv_import_failed", cause)
            call.respondInternal()
            return@post
        }

        call.respondImportResult(importResult)
    }
}

private suspend fun ApplicationCall.respondImportResult(result: DomainResult<CsvImportService.ImportReport>) {
    result.fold(
        onSuccess = { report ->
            val response = ImportReportResponse(
                inserted = report.inserted,
                skippedDuplicates = report.skippedDuplicates,
                failed = report.failed.map { failure ->
                    ImportFailedItem(line = failure.lineNumber, error = failure.message)
                },
            )
            respond(response)
        },
        onFailure = { error -> handleDomainError(error) },
    )
}

private fun String?.toPortfolioIdOrNull(): UUID? = this?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
    runCatchingNonFatal { UUID.fromString(value) }.getOrNull()
}

internal val PortfolioImportDepsKey = AttributeKey<PortfolioImportDeps>("PortfolioImportDeps")

internal val ImportByUrlLimiterHolderKey = AttributeKey<ImportByUrlRateLimiterHolder>("ImportByUrlLimiterHolder")
internal val ImportByUrlSettingsKey = AttributeKey<ImportByUrlSettings>("ImportByUrlSettings")

internal data class PortfolioImportDeps(
    val importCsv: suspend (UUID, Reader) -> DomainResult<CsvImportService.ImportReport>,
    val uploadSettings: UploadSettings,
    val downloadCsv: suspend (String, Long) -> RemoteCsv,
)

internal class ImportByUrlRateLimiterHolder(private val clock: Clock) {
    private val limiters = ConcurrentHashMap<RateLimitConfig, RateLimiter>()

    fun limiter(config: RateLimitConfig): RateLimiter = limiters.computeIfAbsent(config) { RateLimiter(it, clock) }
}

internal data class ImportByUrlSettings(
    val enabled: Boolean,
    val rateLimit: RateLimitConfig,
)

internal data class RemoteCsv(
    val contentType: ContentType?,
    val bytes: ByteArray,
)

private fun Application.importByUrlRateLimiter(config: RateLimitConfig): RateLimiter {
    val attributes = attributes
    val holder = if (attributes.contains(ImportByUrlLimiterHolderKey)) {
        attributes[ImportByUrlLimiterHolderKey]
    } else {
        val created = ImportByUrlRateLimiterHolder(Clock.systemUTC())
        attributes.put(ImportByUrlLimiterHolderKey, created)
        created
    }
    return holder.limiter(config)
}

internal fun Application.setImportByUrlLimiterHolder(holder: ImportByUrlRateLimiterHolder) {
    attributes.put(ImportByUrlLimiterHolderKey, holder)
}

internal fun Application.setImportByUrlSettings(settings: ImportByUrlSettings) {
    attributes.put(ImportByUrlSettingsKey, settings)
}

private fun ApplicationCall.rateLimitSubject(userId: String?): String {
    if (userId != null) {
        return userId
    }
    val forwarded = request.headers[HttpHeaders.XForwardedFor]
        ?.split(',')
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
    val remote = forwarded
        ?: request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.headers[HttpHeaders.Host]?.substringBefore(':')
    return remote?.takeIf { it.isNotBlank() } ?: "anonymous"
}

private fun ApplicationCall.importByUrlSettings(): ImportByUrlSettings {
    val attributes = application.attributes
    if (attributes.contains(ImportByUrlSettingsKey)) {
        return attributes[ImportByUrlSettingsKey]
    }
    val conf = application.environment.config
    val enabled = conf.propertyOrNull("import.byUrlEnabled")?.getString()?.toBoolean() ?: false
    val capacity = conf.property("import.byUrlRateLimit.capacity").getString().toInt()
    val refill = conf.property("import.byUrlRateLimit.refillPerMinute").getString().toInt()
    return ImportByUrlSettings(enabled = enabled, rateLimit = RateLimitConfig(capacity, refill))
}

private fun ApplicationCall.importDeps(): PortfolioImportDeps {
    val attributes = application.attributes
    if (attributes.contains(PortfolioImportDepsKey)) {
        return attributes[PortfolioImportDepsKey]
    }

    val module = application.portfolioModule()
    val integrations = application.integrationsModule()
    val instrumentRepository = module.repositories.instrumentRepository
    val tradeRepository = module.repositories.tradeRepository
    val portfolioService = module.services.portfolioService
    val valuationMethod = module.settings.portfolio.defaultValuationMethod
    val settings = UploadSettings.fromConfig(application.environment.config)
    val importService = CsvImportService(
        instrumentResolver = DatabaseInstrumentResolver(instrumentRepository),
        tradeLookup = DatabaseTradeLookup(tradeRepository),
        portfolioService = portfolioService,
    )
    val downloader = HttpCsvFetcher(integrations.httpClient, application.environment.log)
    val deps = PortfolioImportDeps(
        importCsv = { portfolioId, reader -> importService.import(portfolioId, reader, valuationMethod) },
        uploadSettings = settings,
        downloadCsv = { url, limit -> downloader.fetch(url, limit) },
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

internal class RemoteCsvTooLargeException(val limit: Long) : RuntimeException()

internal class RemoteCsvDownloadException(val code: String, cause: Throwable? = null) : RuntimeException(code, cause)

private fun looksLikeCsv(bytes: ByteArray): Boolean {
    val preview = decodePreview(bytes) ?: return false
    val firstLine = preview.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    val delimiters = charArrayOf(',', ';', '\t')
    return delimiters.any { delimiter -> firstLine.count { it == delimiter } >= 1 }
}

private fun decodePreview(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val size = min(bytes.size, SNIFF_PREVIEW_LIMIT)
    val buffer = ByteBuffer.wrap(bytes, 0, size)
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatchingNonFatal { decoder.decode(buffer).toString() }.getOrNull()
}

private class HttpCsvFetcher(
    private val client: HttpClient,
    private val logger: Logger,
) {
    suspend fun fetch(url: String, limit: Long): RemoteCsv {
        val response = try {
            client.get(url) {
                header(HttpHeaders.Accept, CSV_ACCEPT_HEADER)
            }
        } catch (timeout: HttpRequestTimeoutException) {
            throw RemoteCsvDownloadException("timeout", timeout)
        } catch (redirect: RedirectResponseException) {
            throw RemoteCsvDownloadException("http_${redirect.response.status.value}", redirect)
        } catch (clientError: ClientRequestException) {
            throw RemoteCsvDownloadException("http_${clientError.response.status.value}", clientError)
        } catch (serverError: ServerResponseException) {
            throw RemoteCsvDownloadException("http_${serverError.response.status.value}", serverError)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (cause: Throwable) {
            throw RemoteCsvDownloadException("network", cause)
        }

        val channel = response.bodyAsChannel()
        try {
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > limit) {
                throw RemoteCsvTooLargeException(limit)
            }
            val bytes = readBytesLimited(channel, limit)
            return RemoteCsv(contentType = response.contentType(), bytes = bytes)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (tooLarge: RemoteCsvTooLargeException) {
            throw tooLarge
        } catch (download: RemoteCsvDownloadException) {
            throw download
        } catch (cause: Throwable) {
            logger.warn("csv_download_exception", cause)
            throw RemoteCsvDownloadException("unexpected", cause)
        } finally {
            channel.cancel()
        }
    }

    private suspend fun readBytesLimited(channel: io.ktor.utils.io.ByteReadChannel, limit: Long): ByteArray {
        val packet = channel.readRemaining(limit + 1)
        val bytes = packet.readBytes()
        if (bytes.size > limit) {
            channel.cancel()
            throw RemoteCsvTooLargeException(limit)
        }
        return bytes
    }
}

private class DatabaseInstrumentResolver(
    private val repository: InstrumentRepository,
) : CsvImportService.InstrumentResolver {
    override suspend fun findBySymbol(
        exchange: String,
        board: String?,
        symbol: String,
    ): DomainResult<CsvImportService.InstrumentRef?> = runCatchingNonFatal {
        repository.findBySymbol(exchange, board, symbol)?.let { CsvImportService.InstrumentRef(it.instrumentId) }
    }

    override suspend fun findByAlias(
        alias: String,
        source: String,
    ): DomainResult<CsvImportService.InstrumentRef?> = runCatchingNonFatal {
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
    ): DomainResult<Boolean> = runCatchingNonFatal {
        repository.findByExternalId(externalId)?.portfolioId == portfolioId
    }

    override suspend fun existsBySoftKey(key: CsvImportService.SoftTradeKey): DomainResult<Boolean> = runCatchingNonFatal {
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
