package moex

import http.TestHttpFixtures
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoexIssClientTest {
    @Test
    fun getSecuritiesTqbrSucceedsWithConfiguredClient() =
        runTest {
            val cfg = TestHttpFixtures.defaultCfg()
            val metrics = TestHttpFixtures.metrics()
            val http =
                TestHttpFixtures.client(cfg, metrics) {
                    addHandler { _ ->
                        respond(
                            content =
                            """
                                {
                                  "securities": {
                                    "columns": ["SECID","SHORTNAME","BOARDID","LOTSIZE","FACEUNIT","PREVPRICE","LAST","SECTYPE","ISSUESIZEPLACED"],
                                    "data": [["SBER","Sberbank","TQBR","10","RUB","250.10","251.00","share","1"]]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                listOf(ContentType.Application.Json.toString()),
                            ),
                        )
                    }
                }
            val circuitBreaker = TestHttpFixtures.cb(cfg, metrics)
            val client =
                MoexIssClient(
                    http,
                    circuitBreaker,
                    metrics,
                    cacheTtlMs = 15_000L,
                )
            client.setBaseUrl("https://example.test")

            val result = client.getSecuritiesTqbr(listOf("SBER"))
            assertTrue(result.isSuccess, "expected success but got ${result.exceptionOrNull()}")
            assertEquals(
                "SBER",
                result
                    .getOrThrow()
                    .securities
                    .first()
                    .code,
            )

            http.close()
        }
}
