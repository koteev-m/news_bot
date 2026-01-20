package mtproto

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class HttpMtprotoViewsClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `sends normalized payload with increment flag`() = runTest {
        var capturedBody: String? = null
        var apiKeyHeader: String? = null
        val engine = MockEngine { request ->
            apiKeyHeader = request.headers["X-Api-Key"]
            val body = request.body as TextContent
            capturedBody = body.text
            respond(
                content = """{"views":{"1":10,"2":20}}""",
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentType, "application/json") }
            )
        }

        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val mtproto = HttpMtprotoViewsClient(client, "http://gateway", apiKey = "secret")

        val result = mtproto.getViews("channel", listOf(2, 2, 1, 0, -1), increment = false)

        assertEquals(mapOf(1 to 10L, 2 to 20L), result)
        assertEquals("secret", apiKeyHeader)
        val payload = json.parseToJsonElement(requireNotNull(capturedBody)).jsonObject
        assertEquals("@channel", payload["channel"]?.jsonPrimitive?.content)
        assertEquals(false, payload["increment"]?.jsonPrimitive?.boolean)
        val ids = payload["ids"]?.jsonArray?.map { it.jsonPrimitive.int }
        assertEquals(listOf(2, 1), ids)
    }

    @Test
    fun `maps server error to http status error`() = runTest {
        val engine = MockEngine {
            respond(
                content = "boom",
                status = HttpStatusCode.InternalServerError,
                headers = Headers.build { append(HttpHeaders.ContentType, "text/plain") }
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val mtproto = HttpMtprotoViewsClient(client, "http://gateway")

        assertFailsWith<MtprotoViewsClientError.HttpStatusError> {
            mtproto.getViews("channel", listOf(1), increment = false)
        }
    }

    @Test
    fun `maps timeout to timeout error`() = runTest {
        val engine = MockEngine { throw HttpRequestTimeoutException(HttpRequestBuilder()) }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val mtproto = HttpMtprotoViewsClient(client, "http://gateway")

        val error = assertFailsWith<MtprotoViewsClientError.TimeoutError> {
            mtproto.getViews("channel", listOf(1), increment = false)
        }
        assertNotNull(error.message)
    }
}
