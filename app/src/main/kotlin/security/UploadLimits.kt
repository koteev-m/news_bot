package security

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.BaseApplicationPlugin
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.configOrNull
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlin.io.DEFAULT_BUFFER_SIZE
import common.runCatchingNonFatal

object UploadGuard : BaseApplicationPlugin<Application, UploadGuard.Config, UploadGuard> {

    class Config {
        var csvMaxBytes: Long = 1_048_576
        var allowedCsvContentTypes: Set<ContentType> = setOf(
            ContentType.Text.CSV,
            ContentType.Application.OctetStream,
            ContentType.parse("application/vnd.ms-excel"),
        )
    }

    override val key: AttributeKey<UploadGuard> = AttributeKey("UploadGuard")

    override fun install(pipeline: Application, configure: Config.() -> Unit): UploadGuard {
        val pluginConfig = Config().apply(configure)
        require(pluginConfig.csvMaxBytes > 0) { "upload.csvMaxBytes must be greater than zero" }
        require(pluginConfig.allowedCsvContentTypes.isNotEmpty()) { "upload.allowedCsvContentTypes must not be empty" }

        pipeline.intercept(ApplicationCallPipeline.Plugins) {
            val isMultipart = call.isMultipartFormData()
            if (isMultipart) {
                val contentLength = call.request.contentLength()
                if (contentLength != null && contentLength > pluginConfig.csvMaxBytes) {
                    call.respondPayloadTooLarge(pluginConfig.csvMaxBytes)
                    finish()
                    return@intercept
                }
            }

            try {
                proceed()
            } catch (exception: PayloadTooLargeException) {
                call.respondPayloadTooLarge(exception.limit)
                finish()
            } catch (_: UnsupportedCsvMediaTypeException) {
                call.respondUnsupportedMediaType()
                finish()
            }
        }

        pipeline.receivePipeline.intercept(ApplicationReceivePipeline.After) { received ->
            if (received !is MultiPartData) return@intercept
            if (!call.isMultipartFormData()) return@intercept

            val guarded = GuardedMultiPartData(
                delegate = received,
                limit = pluginConfig.csvMaxBytes,
                allowedContentTypes = pluginConfig.allowedCsvContentTypes,
            )
            proceedWith(guarded)
        }

        return this
    }

    private class PayloadTooLargeException(val limit: Long) : RuntimeException()

    private object UnsupportedCsvMediaTypeException : RuntimeException()

    private class GuardedMultiPartData(
        private val delegate: MultiPartData,
        private val limit: Long,
        private val allowedContentTypes: Set<ContentType>,
    ) : MultiPartData {
        override suspend fun readPart(): PartData? {
            val part = delegate.readPart() ?: return null
            return when (part) {
                is PartData.FileItem -> guardFileItem(part)
                else -> part
            }
        }

        private suspend fun guardFileItem(part: PartData.FileItem): PartData.FileItem {
            val contentType = part.contentType
            if (!isAllowed(contentType)) {
                part.dispose()
                throw UnsupportedCsvMediaTypeException
            }

            val declaredLength = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > limit) {
                part.dispose()
                throw PayloadTooLargeException(limit)
            }

            val bytes = try {
                part.provider().readBytesWithin(limit)
            } catch (cancellation: CancellationException) {
                part.dispose()
                throw cancellation
            } catch (err: Error) {
                part.dispose()
                throw err
            } catch (cause: Throwable) {
                part.dispose()
                throw cause
            }

            return PartData.FileItem(
                provider = { ByteReadChannel(bytes) },
                dispose = part.dispose,
                partHeaders = part.headers,
            )
        }

        private fun isAllowed(contentType: ContentType?): Boolean =
            contentType != null && allowedContentTypes.any { actual -> contentType.match(actual) }
    }

    private suspend fun ByteReadChannel.readBytesWithin(limit: Long): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        var total = 0L

        while (true) {
            val remaining = limit - total
            if (remaining <= 0L) {
                val extra = readAvailable(buffer, 0, 1)
                when {
                    extra == -1 -> break
                    extra == 0 -> {
                        if (isClosedForRead) break
                        awaitContent(1)
                        continue
                    }
                    else -> {
                        cancel()
                        throw PayloadTooLargeException(limit)
                    }
                }
            } else {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = readAvailable(buffer, 0, toRead)
                when {
                    read == -1 -> break
                    read == 0 -> {
                        if (isClosedForRead) break
                        awaitContent(1)
                        continue
                    }
                    else -> {
                        total += read
                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        closedCause?.let { throw it }
        return output.toByteArray()
    }

    private suspend fun ApplicationCall.respondPayloadTooLarge(limit: Long) {
        respondText(
            text = """{"error":"payload_too_large","limit":$limit}""",
            status = HttpStatusCode.PayloadTooLarge,
            contentType = ContentType.Application.Json,
        )
    }

    private suspend fun ApplicationCall.respondUnsupportedMediaType() {
        respondText(
            text = """{"error":"unsupported_media_type"}""",
            status = HttpStatusCode.UnsupportedMediaType,
            contentType = ContentType.Application.Json,
        )
    }

    private fun ApplicationCall.isMultipartFormData(): Boolean =
        runCatchingNonFatal { request.contentType() }
            .getOrNull()
            ?.match(ContentType.MultiPart.FormData)
            ?: false
}

fun Application.installUploadGuard() {
    val uploadConfig = environment.config.configOrNull("upload")
    install(UploadGuard) {
        uploadConfig?.let { cfg ->
            cfg.longProperty("csvMaxBytes")?.let { csvMaxBytes = it }
            cfg.contentTypeSet("allowedCsvContentTypes")?.let { allowedCsvContentTypes = it }
        }
    }
}

private fun ApplicationConfig.longProperty(name: String): Long? {
    val rawValue = propertyOrNull(name)?.getString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsed = rawValue.toLongOrNull()
        ?: throw IllegalArgumentException("upload.$name must be a positive integer")
    require(parsed > 0) { "upload.$name must be greater than zero" }
    return parsed
}

private fun ApplicationConfig.contentTypeSet(name: String): Set<ContentType>? {
    val values = propertyOrNull(name)?.getList()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
    if (values.isEmpty()) return null
    val parsed = values.map { value ->
        try {
            ContentType.parse(value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (cause: Throwable) {
            throw IllegalArgumentException("upload.$name contains invalid content type: $value", cause)
        }
    }
    return parsed.toSet()
}
