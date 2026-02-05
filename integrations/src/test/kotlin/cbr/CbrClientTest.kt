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

class CbrClientTest {
    @Test
    fun getXmlDailyParsesResponse() =
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
                                <ValCurs Date="20.05.2024" name="Foreign Currency Market">
                                  <Valute ID="R01235">
                                    <CharCode>USD</CharCode>
                                    <Nominal>1</Nominal>
                                    <Value>90,1234</Value>
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
            val client = CbrClient(http, cb, metrics, cacheTtlMs = 10_000L)
            client.setBaseUrl("https://example.test")

            val result = client.getXmlDaily(null)
            assertTrue(result.isSuccess, "expected success but got ${result.exceptionOrNull()}")
            val rates = result.getOrThrow()
            assertEquals("USD", rates.first().currencyCode)

            http.close()
        }

    @Test
    fun getXmlDailyCachesByDate() =
        runTest {
            val cfg = TestHttpFixtures.defaultCfg()
            val metrics = TestHttpFixtures.metrics()
            var calls = 0
            val http =
                TestHttpFixtures.client(cfg, metrics) {
                    addHandler {
                        calls += 1
                        respond(
                            content =
                            """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <ValCurs Date="21.05.2024" name="Foreign Currency Market">
                                  <Valute ID="R01235">
                                    <CharCode>USD</CharCode>
                                    <Nominal>1</Nominal>
                                    <Value>90,0000</Value>
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
            val client = CbrClient(http, cb, metrics, cacheTtlMs = 60_000L)
            client.setBaseUrl("https://example.test")

            val first = client.getXmlDaily(LocalDate.parse("2024-05-21"))
            val second = client.getXmlDaily(LocalDate.parse("2024-05-21"))
            assertTrue(first.isSuccess, "expected success but got ${first.exceptionOrNull()}")
            assertTrue(second.isSuccess, "expected success but got ${second.exceptionOrNull()}")
            assertEquals(1, calls)

            http.close()
        }
}
