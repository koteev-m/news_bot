package it

import data.moex.Netflow2Client
import http.CircuitBreaker
import http.CircuitBreakerCfg
import http.HttpClients
import http.HttpPoolConfig
import http.IntegrationsHttpConfig
import http.IntegrationsMetrics
import http.RetryCfg
import http.TimeoutMs
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import netflow2.Netflow2Loader
import netflow2.Netflow2PullWindow
import observability.feed.Netflow2Metrics
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import repo.PostgresNetflow2Repository
import repo.tables.MoexNetflow2Table
import java.time.Clock
import java.time.LocalDate

@Tag("integration")
class Netflow2EndToEndIT {
    @Test
    fun `loads via http client and updates rows`() =
        runBlocking {
            TestDb.withMigratedDatabase { _ ->
                val integrationsRegistry = SimpleMeterRegistry()
                val feedRegistry = SimpleMeterRegistry()
                val httpConfig =
                    IntegrationsHttpConfig(
                        userAgent = "test-agent",
                        timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
                        retry =
                        RetryCfg(
                            maxAttempts = 2,
                            baseBackoffMs = 1,
                            jitterMs = 0,
                            respectRetryAfter = false,
                            retryOn = listOf(),
                        ),
                        circuitBreaker =
                        CircuitBreakerCfg(
                            failuresThreshold = 5,
                            windowSeconds = 60,
                            openSeconds = 10,
                            halfOpenMaxCalls = 1,
                        ),
                    )
                val integrationsMetrics = IntegrationsMetrics(integrationsRegistry)
                val feedMetrics = Netflow2Metrics(feedRegistry)

                val firstWindowInitial =
                    """
                    meta
                    SECID;DATE;P30;P70;P100;PV30;PV70;PV100;VOL;OI
                    SBER;2018-01-01;1;;;;;;10;11
                    SBER;2020-12-31;2;;;;;;;
                    """.trimIndent()
                val firstWindowUpdated =
                    """
                    meta
                    SECID;DATE;P30;P70;P100;PV30;PV70;PV100;VOL;OI
                    SBER;2018-01-01;5;;;;;;10;11
                    SBER;2020-12-31;2;;;;;;;
                    """.trimIndent()
                val secondWindowPayload =
                    """
                    meta
                    SECID;DATE;P30;P70;P100;PV30;PV70;PV100;VOL;OI
                    SBER;2021-01-01;;;;;;;15;20
                    SBER;2021-01-02;;;;;;;16;21
                    """.trimIndent()

                val payloads =
                    mutableMapOf(
                        ("2018-01-01" to "2020-12-31") to firstWindowInitial,
                        ("2021-01-01" to "2021-01-02") to secondWindowPayload,
                    )

                val httpClient =
                    HttpClients.build(
                        cfg = httpConfig,
                        pool = HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30),
                        metrics = integrationsMetrics,
                        clock = Clock.systemUTC(),
                        engineFactory = MockEngine,
                    ) {
                        addHandler { request ->
                            assertEquals(
                                "/iss/analyticalproducts/netflow2/securities/SBER.csv",
                                request.url.encodedPath,
                            )
                            val from = request.url.parameters["from"] ?: error("from missing")
                            val till = request.url.parameters["till"] ?: error("till missing")
                            val payload = payloads[from to till] ?: error("unexpected window $from-$till")
                            respond(payload)
                        }
                    }

                try {
                    val client =
                        Netflow2Client(
                            client = httpClient,
                            circuitBreaker =
                            CircuitBreaker(
                                "netflow2",
                                httpConfig.circuitBreaker,
                                integrationsMetrics,
                                Clock.systemUTC(),
                            ),
                            metrics = integrationsMetrics,
                            retryCfg = httpConfig.retry,
                        )
                    val repository = PostgresNetflow2Repository()
                    val loader = Netflow2Loader(client, repository, feedMetrics)

                    val from = LocalDate.of(2018, 1, 1)
                    val till = LocalDate.of(2021, 1, 2)
                    val windows = Netflow2PullWindow.splitInclusive(from, till)

                    val first = loader.upsert("sber", from, till)
                    val countAfterFirst = TestDb.tx { MoexNetflow2Table.selectAll().count() }

                    payloads["2018-01-01" to "2020-12-31"] = firstWindowUpdated
                    val second = loader.upsert("sber", from, till)
                    val countAfterSecond = TestDb.tx { MoexNetflow2Table.selectAll().count() }

                    val updatedRow =
                        TestDb.tx {
                            MoexNetflow2Table
                                .select {
                                    (MoexNetflow2Table.ticker eq "SBER") and
                                        (MoexNetflow2Table.date eq LocalDate.of(2018, 1, 1))
                                }.single()[MoexNetflow2Table.p30]
                        }

                    assertEquals(2, windows.size)
                    assertEquals(4, first.rowsFetched)
                    assertEquals(4L, countAfterFirst)
                    assertEquals(4L, countAfterSecond)
                    assertEquals(5L, updatedRow)
                    assertEquals(LocalDate.of(2021, 1, 2), second.maxDate)
                } finally {
                    httpClient.close()
                }
            }
        }
}
