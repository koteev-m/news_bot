package alerts

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AlertsRepositoryMemory : AlertsRepository {
    private val states = ConcurrentHashMap<Long, FsmState>()
    private val dailyCounts = ConcurrentHashMap<Pair<Long, LocalDate>, AtomicInteger>()
    private val lastDateByUser = ConcurrentHashMap<Long, LocalDate>()

    override fun getState(userId: Long): FsmState = states[userId] ?: FsmState.Idle

    override fun setState(
        userId: Long,
        state: FsmState,
    ) {
        states[userId] = state
    }

    override fun getDailyPushCount(
        userId: Long,
        date: LocalDate,
    ): Int = dailyCounts[userId to date]?.get() ?: 0

    override fun incDailyPushCount(
        userId: Long,
        date: LocalDate,
    ) {
        val previousDate = lastDateByUser.put(userId, date)
        if (previousDate != null && previousDate != date) {
            dailyCounts.remove(userId to previousDate)
        }
        dailyCounts.computeIfAbsent(userId to date) { AtomicInteger(0) }.incrementAndGet()
    }
}
