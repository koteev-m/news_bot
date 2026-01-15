package routes

import alerts.INTERNAL_TOKEN_HEADER
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import data.moex.Netflow2ClientError
import netflow2.Netflow2LoadResult
import netflow2.Netflow2Loader
import netflow2.Netflow2LoaderError

class Netflow2RoutesTest {
    @Test
    fun `returns load result`() = testApplication {
        val loader = io.mockk.mockk<Netflow2Loader>()
        val from = LocalDate.of(2024, 1, 1)
        val till = LocalDate.of(2024, 1, 2)
        coEvery { loader.upsert("SBER", from, till) } returns Netflow2LoadResult(
            sec = "SBER",
            from = from,
            till = till,
            windows = 2,
            rowsFetched = 3,
            rowsUpserted = 3,
            maxDate = till
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            routing { netflow2AdminRoutes(loader, "secret") }
        }

        val response = client.post("/admin/netflow2/load?sec=SBER&from=2024-01-01&till=2024-01-02") {
            headers { append(INTERNAL_TOKEN_HEADER, "secret") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()) as JsonObject
        assertEquals("SBER", body["sec"]?.toString()?.trim('"'))
        assertEquals("2024-01-02", body["maxDate"]?.toString()?.trim('"'))
        assertEquals(2, body["windows"]?.toString()?.toInt())
        assertEquals(3, body["rows"]?.toString()?.toInt())
    }

    @Test
    fun `validates required parameters`() = testApplication {
        val loader = io.mockk.mockk<Netflow2Loader>()

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            routing { netflow2AdminRoutes(loader, "token") }
        }

        val response = client.post("/admin/netflow2/load?sec=&from=2024-01-01") {
            headers { append(INTERNAL_TOKEN_HEADER, "token") }
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("error"))
    }

    @Test
    fun `rejects ticker with whitespace`() = testApplication {
        val loader = io.mockk.mockk<Netflow2Loader>()
        val from = LocalDate.of(2024, 1, 1)
        val till = LocalDate.of(2024, 1, 2)
        coEvery { loader.upsert(any(), from, till) } throws Netflow2LoaderError.ValidationError("ticker must not contain whitespace")

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            routing { netflow2AdminRoutes(loader, "token") }
        }

        val response = client.post("/admin/netflow2/load?sec=S%20BER&from=2024-01-01&till=2024-01-02") {
            headers { append(INTERNAL_TOKEN_HEADER, "token") }
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("error"))
    }

    @Test
    fun `returns not found for unknown sec`() = testApplication {
        val loader = io.mockk.mockk<Netflow2Loader>()
        val from = LocalDate.of(2024, 1, 1)
        val till = LocalDate.of(2024, 1, 2)
        coEvery { loader.upsert("SBERX", from, till) } throws Netflow2ClientError.NotFound("SBERX")

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            routing { netflow2AdminRoutes(loader, "token") }
        }

        val response = client.post("/admin/netflow2/load?sec=SBERX&from=2024-01-01&till=2024-01-02") {
            headers { append(INTERNAL_TOKEN_HEADER, "token") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("sec not found"))
    }

    @Test
    fun `propagates assertion error from loader`() {
        val from = LocalDate.of(2024, 1, 1)
        val till = LocalDate.of(2024, 1, 2)

        testApplication {
            val loader = io.mockk.mockk<Netflow2Loader>()
            coEvery { loader.upsert("SBER", from, till) } throws AssertionError("boom")

            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
                routing { netflow2AdminRoutes(loader, "token") }
            }

            assertFailsWith<AssertionError> {
                client.post("/admin/netflow2/load?sec=SBER&from=2024-01-01&till=2024-01-02") {
                    headers { append(INTERNAL_TOKEN_HEADER, "token") }
                }
            }
        }
    }
}
