package billing.stars

import billing.stars.StarsMetrics.CNT_CACHE
import billing.stars.StarsMetrics.CNT_BOUNDED_STALE
import billing.stars.StarsMetrics.CNT_OUTCOME
import billing.stars.StarsMetrics.GAUGE_CACHE_AGE
import billing.stars.StarsMetrics.GAUGE_CACHE_TTL
import billing.stars.StarsMetrics.GAUGE_RATE_LIMIT_REMAINING
import billing.stars.StarsMetrics.LABEL_OUTCOME
import billing.stars.StarsMetrics.LABEL_REASON
import billing.stars.StarsMetrics.LABEL_STATE
import billing.stars.StarsOutcomes.DECODE_ERROR
import billing.stars.StarsOutcomes.SERVER
import billing.stars.StarsOutcomes.STALE_RETURNED
import billing.stars.StarsOutcomes.SUCCESS
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class StarsServiceTest {
    @Test
    fun `returns stale cached balance on server error`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = FakeStarsClient(
            sequence = listOf(
                Result.success(
                    BotStarBalance(
                        available = 5,
                        pending = 1,
                        updatedAtEpochSeconds = 1234,
                    ),
                ),
                Result.failure(StarsClientServerError(500)),
            ),
        )

        val service = StarsService(client, ttlSeconds = 0, meterRegistry = registry)

        val fresh = service.getBotStarBalance().balance
        val stale = service.getBotStarBalance().balance
        assertEquals(true, stale.stale)
        assertEquals(fresh.available, stale.available)
        assertEquals(fresh.pending, stale.pending)
        assertEquals(fresh.updatedAtEpochSeconds, stale.updatedAtEpochSeconds)

        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, SUCCESS).count())
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, STALE_RETURNED).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.MISS.label()).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.STALE.label()).count())
    }

    @Test
    fun `returns stale cached balance on rate limit`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = FakeStarsClient(
            sequence = listOf(
                Result.success(
                    BotStarBalance(
                        available = 2,
                        pending = 0,
                        updatedAtEpochSeconds = 999,
                    ),
                ),
                Result.failure(StarsClientRateLimited()),
            ),
        )

        val service = StarsService(client, ttlSeconds = 0, meterRegistry = registry)

        val fresh = service.getBotStarBalance().balance
        val stale = service.getBotStarBalance().balance

        assertEquals(true, stale.stale)
        assertEquals(fresh.available, stale.available)
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, SUCCESS).count())
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, STALE_RETURNED).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.MISS.label()).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.STALE.label()).count())
    }

    @Test
    fun `returns stale cached balance on bad request`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = FakeStarsClient(
            sequence = listOf(
                Result.success(
                    BotStarBalance(
                        available = 3,
                        pending = 1,
                        updatedAtEpochSeconds = 555,
                    ),
                ),
                Result.failure(StarsClientBadRequest(400)),
            ),
        )

        val service = StarsService(client, ttlSeconds = 0, meterRegistry = registry)

        val fresh = service.getBotStarBalance().balance
        val stale = service.getBotStarBalance().balance

        assertEquals(true, stale.stale)
        assertEquals(fresh.pending, stale.pending)
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, SUCCESS).count())
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, STALE_RETURNED).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.MISS.label()).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.STALE.label()).count())
    }

    @Test
    fun `returns stale cached balance on decode error`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = FakeStarsClient(
            sequence = listOf(
                Result.success(
                    BotStarBalance(
                        available = 4,
                        pending = 1,
                        updatedAtEpochSeconds = 321,
                    ),
                ),
                Result.failure(StarsClientDecodeError(IllegalStateException("bad payload"))),
            ),
        )

        val service = StarsService(client, ttlSeconds = 0, meterRegistry = registry)

        val fresh = service.getBotStarBalance().balance
        val stale = service.getBotStarBalance().balance

        assertTrue(stale.stale)
        assertEquals(fresh.available, stale.available)
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, SUCCESS).count())
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, STALE_RETURNED).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.MISS.label()).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.STALE.label()).count())
    }

    @Test
    fun `emits decode_error outcome when no cache`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = FakeStarsClient(sequence = listOf(Result.failure(StarsClientDecodeError(Exception("boom")))))

        val service = StarsService(client, ttlSeconds = 0, meterRegistry = registry)

        runCatching { service.getBotStarBalance() }
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, DECODE_ERROR).count())
        assertEquals(0.0, registry.find(CNT_CACHE).counter()?.count() ?: 0.0)
    }

    @Test
    fun `coalesces concurrent refresh when cache expired`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = CountingStarsClient(
            balance = BotStarBalance(available = 7, pending = 0, updatedAtEpochSeconds = 777),
        )
        val service = StarsService(client, ttlSeconds = 1, meterRegistry = registry)

        service.getBotStarBalance() // warm cache
        delay(1_100) // expire TTL

        coroutineScope {
            List(5) { async { service.getBotStarBalance() } }.awaitAll()
        }

        assertEquals(2, client.callCount) // one warm-up, one coalesced refresh
        assertEquals(2.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.MISS.label()).count())
        assertEquals(4.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.HIT.label()).count())

        assertNotNull(registry.find(GAUGE_CACHE_AGE).gauge())
        assertEquals(1.0, registry.find(GAUGE_CACHE_TTL).gauge()?.value())
    }

    @Test
    fun `does not return stale when cache exceeds max age`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = FakeStarsClient(
            sequence = listOf(
                Result.success(
                    BotStarBalance(
                        available = 1,
                        pending = 0,
                        updatedAtEpochSeconds = 100,
                    ),
                ),
                Result.failure(StarsClientServerError(500)),
            ),
        )

        val service = StarsService(client, ttlSeconds = 0, maxStaleSeconds = 1, meterRegistry = registry)

        service.getBotStarBalance()
        delay(2_100)

        val result = runCatching { service.getBotStarBalance() }
        assertTrue(result.isFailure)
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, SUCCESS).count())
        assertEquals(1.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, SERVER).count())
        assertEquals(1.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.MISS.label()).count())
        assertEquals(0.0, registry.counter(CNT_CACHE, LABEL_STATE, CacheState.STALE.label()).count())
        assertEquals(1.0, registry.counter(CNT_BOUNDED_STALE, LABEL_REASON, SERVER).count())
    }

    @Test
    fun `cache age gauge grows and resets after refresh`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = FakeStarsClient(
            sequence = listOf(
                Result.success(
                    BotStarBalance(
                        available = 3,
                        pending = 1,
                        updatedAtEpochSeconds = 200,
                    ),
                ),
                Result.success(
                    BotStarBalance(
                        available = 4,
                        pending = 0,
                        updatedAtEpochSeconds = 300,
                    ),
                ),
            ),
        )

        val service = StarsService(client, ttlSeconds = 0, maxStaleSeconds = 10, meterRegistry = registry)

        service.getBotStarBalance()
        delay(1_100)
        val ageBeforeRefresh = registry.find(GAUGE_CACHE_AGE).gauge()?.value() ?: 0.0

        service.getBotStarBalance()
        val ageAfterRefresh = registry.find(GAUGE_CACHE_AGE).gauge()?.value() ?: 0.0

        assertTrue(ageBeforeRefresh > 0.0)
        assertTrue(ageAfterRefresh < ageBeforeRefresh)
    }

    @Test
    fun `skips telegram calls while within rate limit window`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = object : StarsClient(
            botToken = "test",
            config = StarsClientConfig(
                connectTimeoutMs = 1,
                readTimeoutMs = 1,
                retryMax = 0,
                retryBaseDelayMs = 1,
            ),
            client = HttpClient(MockEngine) {
                engine { addHandler { error("unexpected http call in test") } }
            },
        ) {
            var callCount = 0
            override suspend fun getBotStarBalance(): BotStarBalance {
                callCount += 1
                return when (callCount) {
                    1 -> BotStarBalance(available = 9, pending = 0, updatedAtEpochSeconds = 111)
                    else -> throw StarsClientRateLimited(retryAfterSeconds = 5)
                }
            }
        }

        val service = StarsService(client, ttlSeconds = 0, maxStaleSeconds = 20, meterRegistry = registry)

        val first = service.getBotStarBalance()
        val second = service.getBotStarBalance()
        val third = service.getBotStarBalance()

        assertEquals(CacheState.MISS, first.cacheState)
        assertEquals(CacheState.STALE, second.cacheState)
        assertEquals(CacheState.STALE, third.cacheState)
        assertEquals(2.0, registry.counter(CNT_OUTCOME, LABEL_OUTCOME, STALE_RETURNED).count())
        assertTrue(third.balance.stale)
        assertEquals(2, client.callCount)
    }

    @Test
    fun `rate limit remaining gauge decreases over time`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = object : StarsClient(
            botToken = "test",
            config = StarsClientConfig(
                connectTimeoutMs = 1,
                readTimeoutMs = 1,
                retryMax = 0,
                retryBaseDelayMs = 1,
            ),
            client = HttpClient(MockEngine) {
                engine { addHandler { error("unexpected http call in test") } }
            },
        ) {
            var callCount = 0
            override suspend fun getBotStarBalance(): BotStarBalance {
                callCount += 1
                return when (callCount) {
                    1 -> BotStarBalance(available = 4, pending = 1, updatedAtEpochSeconds = 200)
                    else -> throw StarsClientRateLimited(retryAfterSeconds = 3)
                }
            }
        }

        val service = StarsService(client, ttlSeconds = 0, maxStaleSeconds = 20, meterRegistry = registry)

        service.getBotStarBalance()
        service.getBotStarBalance()

        val gauge = registry.find(GAUGE_RATE_LIMIT_REMAINING).gauge()
        val initial = gauge?.value()
        assertNotNull(initial)
        assertTrue(initial >= 1.0)

        delay(1200)

        val later = gauge?.value()
        assertNotNull(later)
        assertTrue(later < initial)
        assertTrue(later >= 0.0)
    }

    @Test
    fun `rate limit gauge resets after successful refresh`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val client = object : StarsClient(
            botToken = "test",
            config = StarsClientConfig(
                connectTimeoutMs = 1,
                readTimeoutMs = 1,
                retryMax = 0,
                retryBaseDelayMs = 1,
            ),
            client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        error("unexpected http call in test")
                    }
                }
            },
        ) {
            var callCount = 0
            override suspend fun getBotStarBalance(): BotStarBalance {
                callCount += 1
                return when (callCount) {
                    1 -> BotStarBalance(available = 10, pending = 0, updatedAtEpochSeconds = 10)
                    2 -> throw StarsClientRateLimited(retryAfterSeconds = 1)
                    else -> BotStarBalance(available = 11, pending = 0, updatedAtEpochSeconds = 11)
                }
            }
        }

        val service = StarsService(client, ttlSeconds = 0, maxStaleSeconds = 20, meterRegistry = registry)

        service.getBotStarBalance()
        val stale = service.getBotStarBalance()
        assertEquals(CacheState.STALE, stale.cacheState)

        val gauge = registry.find(GAUGE_RATE_LIMIT_REMAINING).gauge()
        val duringWindow = gauge?.value()
        assertNotNull(duringWindow)
        assertTrue(duringWindow > 0.0)

        delay(1200)

        val refreshed = service.getBotStarBalance()
        assertEquals(CacheState.MISS, refreshed.cacheState)

        val after = gauge?.value()
        assertNotNull(after)
        assertEquals(0.0, after)
    }
}

private class FakeStarsClient(
    private val sequence: List<Result<BotStarBalance>>,
) : StarsClient(
    botToken = "test",
    config = StarsClientConfig(
        connectTimeoutMs = 1,
        readTimeoutMs = 1,
        retryMax = 0,
        retryBaseDelayMs = 1,
    ),
    client = HttpClient(MockEngine) {
        engine {
            addHandler { error("unexpected http call in test") }
        }
    },
) {
    private var index = 0

    override suspend fun getBotStarBalance(): BotStarBalance {
        val next = sequence.getOrNull(index) ?: error("no more responses")
        index += 1
        return next.getOrThrow()
    }
}

private class CountingStarsClient(
    private val balance: BotStarBalance,
) : StarsClient(
    botToken = "test",
    config = StarsClientConfig(
        connectTimeoutMs = 1,
        readTimeoutMs = 1,
        retryMax = 0,
        retryBaseDelayMs = 1,
    ),
    client = HttpClient(MockEngine) {
        engine {
            addHandler { error("unexpected http call in test") }
        }
    },
) {
    var callCount: Int = 0

    override suspend fun getBotStarBalance(): BotStarBalance {
        callCount += 1
        delay(50)
        return balance
    }
}
