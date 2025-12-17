package alerts

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.TestDb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AlertsRepositoryPostgresIT {
    @Test
    fun `state and budget persist in postgres`() = runBlocking {
        TestDb.withMigratedDatabase { dataSource ->
            val repo = AlertsRepositoryPostgres(dataSource)
            val config = EngineConfig(zoneId = ZoneOffset.UTC)
            val service = AlertsService(repo, config, SimpleMeterRegistry())
            val userId = 42L
            val snapshot = MarketSnapshot(
                tsEpochSec = Instant.parse("2024-01-01T00:00:00Z").epochSecond,
                userId = userId,
                items = listOf(SignalItem("AAA", "breakout", "daily", pctMove = 3.0))
            )

            val result = service.onSnapshot(snapshot)
            assertTrue(result.newState is FsmState.COOLDOWN)

            val rehydratedService = AlertsService(repo, config, SimpleMeterRegistry())
            val persistedState = rehydratedService.getState(userId)
            assertEquals(result.newState, persistedState)

            val day1 = LocalDate.of(2024, 1, 1)
            val budgetUser = 99L
            repo.incDailyPushCount(budgetUser, day1)
            repo.incDailyPushCount(budgetUser, day1)
            assertEquals(2, repo.getDailyPushCount(budgetUser, day1))
            val day2 = day1.plusDays(1)
            repo.incDailyPushCount(budgetUser, day2)
            assertEquals(1, repo.getDailyPushCount(budgetUser, day2))
        }
    }

    @Test
    fun `writes commit when autoCommit disabled`() = runBlocking {
        TestDb.withMigratedDatabase { dataSource ->
            val repo = AlertsRepositoryPostgres(dataSource)
            val userId = 777L
            val today = LocalDate.of(2024, 1, 5)

            repo.setState(userId, FsmState.COOLDOWN(untilEpochSec = 1234))
            val secondRepo = AlertsRepositoryPostgres(dataSource)
            assertEquals(FsmState.COOLDOWN(untilEpochSec = 1234), secondRepo.getState(userId))

            repeat(3) { repo.incDailyPushCount(userId, today) }
            assertEquals(3, secondRepo.getDailyPushCount(userId, today))
            val tomorrow = today.plusDays(1)
            secondRepo.incDailyPushCount(userId, tomorrow)
            assertEquals(1, repo.getDailyPushCount(userId, tomorrow))
        }
    }
}
