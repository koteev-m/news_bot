package cbr

import http.TestHttpFixtures
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CbrXmlDailyClientTest {
    @Test
    fun `marks delayed when effective date differs`() =
        runTest {
            val cfg = TestHttpFixtures.defaultCfg()
            val metrics = TestHttpFixtures.metrics()
            val http =
                TestHttpFixtures.client(cfg, metrics) {
                    addHandler { _ ->
                        respond(
                            content =
                            """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <ValCurs Date="17.05.2024" name="Foreign Currency Market">
                                  <Valute ID="R01235">
                                    <CharCode>USD</CharCode>
                                    <Nominal>1</Nominal>
                                    <Value>90,5000</Value>
                                  </Valute>
                                </ValCurs>
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                listOf(ContentType.Application.Xml.toString()),
                            ),
                        )
                    }
                }
            val cb = TestHttpFixtures.cb(cfg, metrics)
            val rawClient = CbrClient(http, cb, metrics, cacheTtlMs = 10_000L)
            rawClient.setBaseUrl("https://example.test")
            val client = CbrXmlDailyClient(rawClient)

            val result = client.fetchDailyRates(LocalDate.parse("2024-05-19"))

            assertEquals(LocalDate.parse("2024-05-17"), result.effectiveDate)
            assertTrue(result.delayed)
            assertEquals("90.50000000", result.ratesToRub.getValue("USD").toPlainString())

            http.close()
        }
}
