package routes

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GuestListRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `JSON preferred when csv has lower q`() = testApplication {
        val storage = prepareActiveListWithManager()
        configureGuestListApp(storage)

        val payload = assertJsonImport(storage) {
            header(HttpHeaders.Accept, "text/csv;q=0.1, application/json;q=1")
        }

        assertEquals(1, payload["accepted_count"]?.jsonPrimitive?.int)
        assertEquals(listOf("Alice"), storage.accepted())
    }

    @Test
    fun `CSV preferred when quality above json`() = testApplication {
        val storage = prepareActiveListWithManager()
        configureGuestListApp(storage)

        val csv = assertCsvImport(storage) {
            header(HttpHeaders.Accept, "application/json;q=0.1, text/csv;q=1")
        }

        val lines = csv.lines()
        assertEquals(2, lines.size)
        assertEquals("accepted_count,rejected_count", lines.first().removePrefix("\uFEFF"))
        assertEquals("1,0", lines[1])
        assertEquals(listOf("Alice"), storage.accepted())
    }

    @Test
    fun `format=csv overrides q=0 Accept`() = testApplication {
        val storage = prepareActiveListWithManager()
        configureGuestListApp(storage)

        val csv = assertCsvImport(storage, format = "csv") {
            header(HttpHeaders.Accept, "text/csv;q=0, application/json;q=1")
        }

        val lines = csv.lines()
        assertEquals("1,0", lines[1])
        assertEquals(listOf("Alice"), storage.accepted())
    }

    @Test
    fun `Accept text-csv q0 does not include csv`() = testApplication {
        val storage = prepareActiveListWithManager()
        configureGuestListApp(storage)

        val payload = assertJsonImport(storage) {
            header(HttpHeaders.Accept, "text/csv;q=0, application/json;q=1")
        }

        assertEquals(1, payload["accepted_count"]?.jsonPrimitive?.int)
        assertEquals(listOf("Alice"), storage.accepted())
    }

    @Test
    fun `Tie CSV and JSON defaults to JSON`() = testApplication {
        val storage = prepareActiveListWithManager()
        configureGuestListApp(storage)

        val payload = assertJsonImport(storage) {
            header(HttpHeaders.Accept, "text/csv, application/json")
        }

        assertEquals(1, payload["accepted_count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `Invalid Accept header falls back to default JSON`() = testApplication {
        val storage = prepareActiveListWithManager()
        configureGuestListApp(storage)

        val payload = assertJsonImport(storage) {
            header(HttpHeaders.Accept, "not-a-media-type")
        }

        assertEquals(1, payload["accepted_count"]?.jsonPrimitive?.int)
        assertEquals(listOf("Alice"), storage.accepted())
    }

    private fun ApplicationTestBuilder.configureGuestListApp(storage: GuestImportStorage) {
        application {
            this.install(ServerContentNegotiation) { json() }
            configureGuestListRoutes(storage)
        }
    }

    private suspend fun ApplicationTestBuilder.assertJsonImport(
        storage: GuestImportStorage,
        format: String? = null,
        block: suspend HttpRequestBuilder.() -> Unit = {},
    ): JsonObject {
        val response = performImport(storage, format, block)
        val contentType = response.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).withoutParameters() }
        assertEquals(ContentType.Application.Json, contentType)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, payload["accepted_count"]?.jsonPrimitive?.int)
        assertEquals(0, payload["rejected_count"]?.jsonPrimitive?.int)
        return payload
    }

    private suspend fun ApplicationTestBuilder.assertCsvImport(
        storage: GuestImportStorage,
        format: String? = null,
        block: suspend HttpRequestBuilder.() -> Unit = {},
    ): String {
        val response = performImport(storage, format, block)
        val contentType = response.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).withoutParameters() }
        assertEquals(ContentType.Text.CSV, contentType)
        return response.bodyAsText()
    }

    private suspend fun ApplicationTestBuilder.performImport(
        storage: GuestImportStorage,
        format: String? = null,
        block: suspend HttpRequestBuilder.() -> Unit,
    ) = createClient {
        expectSuccess = true
    }.use { client ->
        val path = buildString {
            append("/guest-list/import")
            if (format != null) {
                append("?format=")
                append(format)
            }
        }

        client.post(path) {
            contentType(ContentType.Text.CSV)
            setBody("name\nAlice\n")
            block()
        }
    }
}
