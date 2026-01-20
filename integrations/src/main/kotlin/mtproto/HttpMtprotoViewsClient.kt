package mtproto

import common.rethrowIfFatal
import common.runCatchingNonFatal
import http.HttpClientError
import http.toHttpClientError
import interfaces.ChannelViewsClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

class HttpMtprotoViewsClient(
    private val client: HttpClient,
    baseUrl: String,
    private val apiKey: String? = null
) : ChannelViewsClient {
    private val endpoint: String = buildEndpoint(baseUrl)
    private val logger = LoggerFactory.getLogger(HttpMtprotoViewsClient::class.java)

    override suspend fun getViews(channel: String, ids: List<Int>, increment: Boolean): Map<Int, Long> {
        val normalizedChannel = normalizeChannel(channel)
        val normalizedIds = normalizeIds(ids)
        if (normalizedIds.isEmpty()) {
            return emptyMap()
        }

        val startedAt = System.nanoTime()
        val request = MtprotoViewsRequest(
            channel = normalizedChannel,
            ids = normalizedIds,
            increment = increment
        )

        val response = try {
            client.post(endpoint) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                apiKey?.takeIf { it.isNotBlank() }?.let { header(API_KEY_HEADER, it) }
                setBody(request)
            }
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            val durationMs = elapsedMs(startedAt)
            val mapped = mapError(normalizedChannel, normalizedIds.size, durationMs, t)
            throw mapped
        }

        val durationMs = elapsedMs(startedAt)
        if (!response.status.isSuccess()) {
            logger.warn(
                "MTProto views request failed channel={}, ids={}, status={}, latencyMs={}",
                normalizedChannel,
                normalizedIds.size,
                response.status.value,
                durationMs
            )
            throw MtprotoViewsClientError.HttpStatusError(
                status = response.status,
                message = "HTTP ${response.status.value} from MTProto gateway",
                bodySnippet = runCatchingNonFatal { response.bodyAsText() }.getOrNull()
            )
        }

        val payload = response.body<JsonElement>()
        val views = parseViews(payload)
        logger.info(
            "MTProto views request completed channel={}, ids={}, status={}, latencyMs={}",
            normalizedChannel,
            normalizedIds.size,
            response.status.value,
            durationMs
        )
        return views
    }

    private fun normalizeChannel(channel: String): String {
        val trimmed = channel.trim()
        if (trimmed.isEmpty()) {
            throw MtprotoViewsClientError.ValidationError("channel must not be blank")
        }
        return if (trimmed.startsWith("@")) trimmed else "@$trimmed"
    }

    private fun normalizeIds(ids: List<Int>): List<Int> {
        return ids.asSequence()
            .filter { it > 0 }
            .distinct()
            .take(MAX_IDS)
            .toList()
    }

    private fun parseViews(payload: JsonElement): Map<Int, Long> {
        val root = payload as? JsonObject
            ?: throw MtprotoViewsClientError.DeserializationError("Expected JSON object for MTProto views")
        val viewsObject = when (val views = root["views"]) {
            null -> root
            is JsonObject -> views
            else -> throw MtprotoViewsClientError.DeserializationError("Expected 'views' to be a JSON object")
        }

        return viewsObject.entries.mapNotNull { (key, value) ->
            val id = key.toIntOrNull()
            val count = (value as? JsonPrimitive)?.longOrNull
            if (id == null || count == null) {
                null
            } else {
                id to count
            }
        }.toMap()
    }

    private suspend fun mapError(
        channel: String,
        idsCount: Int,
        durationMs: Long,
        t: Throwable
    ): MtprotoViewsClientError {
        val error = when (t) {
            is ResponseException -> {
                val snippet = runCatchingNonFatal { t.response.bodyAsText() }.getOrNull()
                HttpClientError.httpStatusError(t.response.status, endpoint, snippet, t)
            }
            else -> t.toHttpClientError()
        }
        val status = (error as? HttpClientError.HttpStatusError)?.status?.value
        logger.warn(
            "MTProto views request failed channel={}, ids={}, status={}, latencyMs={}",
            channel,
            idsCount,
            status,
            durationMs
        )
        return when (error) {
            is HttpClientError.HttpStatusError -> MtprotoViewsClientError.HttpStatusError(
                status = error.status,
                message = error.message ?: "HTTP ${error.status.value} from MTProto gateway",
                bodySnippet = error.bodySnippet
            )
            is HttpClientError.TimeoutError -> MtprotoViewsClientError.TimeoutError(error.message ?: "timeout", error)
            is HttpClientError.NetworkError -> MtprotoViewsClientError.NetworkError(error.message ?: "network error", error)
            is HttpClientError.DeserializationError -> MtprotoViewsClientError.DeserializationError(
                error.message ?: "deserialization error",
                error
            )
            is HttpClientError.ValidationError -> MtprotoViewsClientError.ValidationError(error.message ?: "validation error")
            is HttpClientError.UnexpectedError -> MtprotoViewsClientError.UnexpectedError(
                error.message ?: "unexpected error",
                error
            )
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / NANOS_PER_MS

    private fun buildEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().removeSuffix("/")
        if (base.isEmpty()) {
            throw MtprotoViewsClientError.ValidationError("baseUrl must not be blank")
        }
        return if (base.endsWith(ENDPOINT_PATH)) base else "$base$ENDPOINT_PATH"
    }

    @Serializable
    private data class MtprotoViewsRequest(
        val channel: String,
        val ids: List<Int>,
        val increment: Boolean
    )

    private companion object {
        private const val NANOS_PER_MS = 1_000_000
        private const val ENDPOINT_PATH = "/messages.getMessagesViews"
        private const val MAX_IDS = 1000
        private const val API_KEY_HEADER = "X-Api-Key"
    }
}

sealed class MtprotoViewsClientError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class ValidationError(message: String) : MtprotoViewsClientError(message)
    class TimeoutError(message: String, cause: Throwable) : MtprotoViewsClientError(message, cause)
    class NetworkError(message: String, cause: Throwable) : MtprotoViewsClientError(message, cause)
    class DeserializationError(message: String, cause: Throwable? = null) : MtprotoViewsClientError(message, cause)
    class UnexpectedError(message: String, cause: Throwable) : MtprotoViewsClientError(message, cause)
    class HttpStatusError(
        val status: HttpStatusCode,
        message: String,
        val bodySnippet: String? = null
    ) : MtprotoViewsClientError(message)
}
