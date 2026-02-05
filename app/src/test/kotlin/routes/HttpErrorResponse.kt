package routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Совместимость для старых тестов, ожидающих HttpErrorResponse(error, details, reason?, limit?)
 * Новый формат сервера: { code, message, details[], traceId }
 */
@Serializable
private data class ApiErrorInternal(
    val code: String,
    val message: String? = null,
    val details: List<String>? = null,
    val traceId: String? = null,
)

data class HttpErrorResponse(
    val error: String,
    val details: List<String> = emptyList(),
    val reason: String? = null,
    val limit: Long? = null,
)

/**
 * Старые тесты часто вызывали конструктор как HttpErrorResponse(json[, path]) —
 * реализуем фабричную функцию с такой сигнатурой.
 */
@Suppress("FunctionName")
fun HttpErrorResponse(
    json: String,
    @Suppress("UNUSED_PARAMETER") path: String? = null,
): HttpErrorResponse {
    val api =
        runCatching { Json.decodeFromString(ApiErrorInternal.serializer(), json) }.getOrElse {
            // Пытаемся прочитать минимально "code" из произвольного JSON
            val elem: JsonElement? = runCatching { Json.parseToJsonElement(json) }.getOrNull()
            val code =
                elem
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content ?: "INTERNAL"
            return HttpErrorResponse(error = code.lowercase())
        }
    val limit =
        api.details?.firstNotNullOfOrNull { detail ->
            when {
                detail.startsWith("retry_after=") -> detail.removePrefix("retry_after=").toLongOrNull()
                detail.startsWith("limit=") -> detail.removePrefix("limit=").toLongOrNull()
                else -> null
            }
        }
    val error =
        when (api.code) {
            "UNSUPPORTED_MEDIA" -> "unsupported_media_type"
            else -> api.code.lowercase()
        }
    val reason = api.details?.firstOrNull() ?: api.message
    return HttpErrorResponse(
        error = error,
        details = api.details ?: emptyList(),
        reason = reason,
        limit = limit,
    )
}
