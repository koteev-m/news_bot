package coingecko

import http.TestHttpFixtures
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoinGeckoClientTest {
    @Test
    fun getSimplePriceParsesPayload() =
        runTest {
            val cfg = TestHttpFixtures.defaultCfg()
            val metrics = TestHttpFixtures.metrics()
            val http =
                TestHttpFixtures.client(cfg, metrics) {
                    addHandler { _ ->
                        respond(
                            content = """{"bitcoin":{"usd":"100000.0","rub":"9000000.0"}}""",
                            status = HttpStatusCode.OK,
                            headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                listOf(ContentType.Application.Json.toString()),
                            ),
                        )
                    }
                }
            val cb = TestHttpFixtures.cb(cfg, metrics)
            val client =
                CoinGeckoClient(
                    http,
                    cb,
                    metrics,
                    clock = Clock.systemUTC(),
                    minRequestInterval = kotlin.time.Duration.ZERO,
                    priceCacheTtlMs = 15_000L,
                )
            client.setBaseUrl("https://example.test")

            val result = client.getSimplePrice(listOf("bitcoin"), listOf("usd", "rub"))
            assertTrue(result.isSuccess, "expected success but got ${result.exceptionOrNull()}")
            val prices = result.getOrThrow()
            assertEquals(BigDecimal("100000.0"), prices.getValue("bitcoin").getValue("usd"))

            http.close()
        }

    @Test
    fun getSimplePriceCachesResponse() =
        runTest {
            val cfg = TestHttpFixtures.defaultCfg()
            val metrics = TestHttpFixtures.metrics()
            var calls = 0
            val http =
                TestHttpFixtures.client(cfg, metrics) {
                    addHandler {
                        calls += 1
                        respond(
                            content = """{"bitcoin":{"usd":"1"}}""",
                            status = HttpStatusCode.OK,
                            headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                listOf(ContentType.Application.Json.toString()),
                            ),
                        )
                    }
                }
            val cb = TestHttpFixtures.cb(cfg, metrics)
            val client =
                CoinGeckoClient(
                    http,
                    cb,
                    metrics,
                    clock = Clock.systemUTC(),
                    minRequestInterval = kotlin.time.Duration.ZERO,
                    priceCacheTtlMs = 60_000L,
                )
            client.setBaseUrl("https://example.test")

            val first = client.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            val second = client.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            assertTrue(first.isSuccess, "expected success but got ${first.exceptionOrNull()}")
            assertTrue(second.isSuccess, "expected success but got ${second.exceptionOrNull()}")
            assertEquals(1, calls)

            http.close()
        }
}
