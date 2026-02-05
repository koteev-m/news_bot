package errors

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

private val json = Json { ignoreUnknownKeys = false }

enum class ErrorCode {
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    UNPROCESSABLE,
    RATE_LIMITED,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_MEDIA,
    INTERNAL,
    CSV_MAPPING_ERROR,
    SELL_EXCEEDS_POSITION,
    IMPORT_BY_URL_DISABLED,
    CHAOS_INJECTED,
    BILLING_DUPLICATE,
    BILLING_APPLY_FAILED,
    FX_RATE_UNAVAILABLE,
}

@Serializable
private data class ErrorCatalogEntry(
    val http: Int,
    @SerialName("message_en") val messageEn: String,
    @SerialName("message_ru") val messageRu: String,
) {
    val status: HttpStatusCode get() = HttpStatusCode.fromValue(http)
}

object ErrorCatalog {
    private val entries: Map<ErrorCode, ErrorCatalogEntry> = load()

    fun message(code: ErrorCode): String = entry(code).messageEn

    fun status(code: ErrorCode): HttpStatusCode = entry(code).status

    private fun entry(code: ErrorCode): ErrorCatalogEntry = entries[code] ?: entries[ErrorCode.INTERNAL]!!

    @OptIn(ExperimentalSerializationApi::class)
    private fun load(): Map<ErrorCode, ErrorCatalogEntry> {
        val resource = resourceStream()
        resource.use {
            val decoded = json.decodeFromStream<Map<String, ErrorCatalogEntry>>(it)
            return decoded.mapKeys { (key, _) -> ErrorCode.valueOf(key) }
        }
    }

    private fun resourceStream(): InputStream =
        requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("errors/catalog.json")) {
            "errors/catalog.json resource is missing"
        }
}

sealed class AppException(
    val errorCode: ErrorCode,
    val details: List<String> = emptyList(),
    open val headers: Map<String, String> = emptyMap(),
    val overrideMessage: String? = null,
    cause: Throwable? = null,
) : RuntimeException(errorCode.name, cause) {
    class BadRequest(
        details: List<String> = emptyList(),
        cause: Throwable? = null,
    ) : AppException(ErrorCode.BAD_REQUEST, details, cause = cause)

    class Unauthorized(
        details: List<String> = emptyList(),
    ) : AppException(ErrorCode.UNAUTHORIZED, details)

    class Forbidden(
        details: List<String> = emptyList(),
    ) : AppException(ErrorCode.FORBIDDEN, details)

    class NotFound(
        details: List<String> = emptyList(),
    ) : AppException(ErrorCode.NOT_FOUND, details)

    class Unprocessable(
        details: List<String> = emptyList(),
        cause: Throwable? = null,
    ) : AppException(ErrorCode.UNPROCESSABLE, details, cause = cause)

    class RateLimited(
        retryAfterSeconds: Long,
        details: List<String> = emptyList(),
    ) : AppException(
        errorCode = ErrorCode.RATE_LIMITED,
        details = details,
        headers = mapOf("Retry-After" to retryAfterSeconds.toString()),
    )

    class PayloadTooLarge(
        limitBytes: Long,
    ) : AppException(ErrorCode.PAYLOAD_TOO_LARGE, details = listOf("limit=$limitBytes"))

    class UnsupportedMedia(
        details: List<String> = emptyList(),
    ) : AppException(ErrorCode.UNSUPPORTED_MEDIA, details)

    class ImportByUrlDisabled : AppException(ErrorCode.IMPORT_BY_URL_DISABLED)

    class ChaosInjected : AppException(ErrorCode.CHAOS_INJECTED)

    class BillingDuplicate(
        details: List<String> = emptyList(),
    ) : AppException(ErrorCode.BILLING_DUPLICATE, details)

    class BillingApplyFailed(
        details: List<String> = emptyList(),
        cause: Throwable? = null,
    ) : AppException(ErrorCode.BILLING_APPLY_FAILED, details, cause = cause)

    class CsvMapping(
        details: List<String> = emptyList(),
        cause: Throwable? = null,
    ) : AppException(ErrorCode.CSV_MAPPING_ERROR, details, cause = cause)

    class SellExceedsPosition(
        details: List<String> = emptyList(),
    ) : AppException(ErrorCode.SELL_EXCEEDS_POSITION, details)

    class Internal(
        details: List<String> = emptyList(),
        cause: Throwable? = null,
    ) : AppException(ErrorCode.INTERNAL, details, cause = cause)

    class Custom(
        code: ErrorCode,
        details: List<String> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        overrideMessage: String? = null,
        cause: Throwable? = null,
    ) : AppException(code, details, headers, overrideMessage, cause)
}
