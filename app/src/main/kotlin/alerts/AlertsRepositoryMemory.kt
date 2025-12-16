package alerts

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AlertsRepositoryMemory : AlertsRepository {
    private val states = ConcurrentHashMap<Long, FsmState>()
    private val dailyCounts = ConcurrentHashMap<Pair<Long, LocalDate>, AtomicInteger>()
    private val lastDateByUser = ConcurrentHashMap<Long, LocalDate>()

    override fun getState(userId: Long): FsmState = states[userId] ?: FsmState.IDLE

    override fun setState(userId: Long, state: FsmState) {
        states[userId] = state
    }

    override fun getDailyPushCount(userId: Long, date: LocalDate): Int {
        return dailyCounts[userId to date]?.get() ?: 0
    }

    override fun incDailyPushCount(userId: Long, date: LocalDate) {
        val lastDate = lastDateByUser[userId]
        if (lastDate != date) {
            dailyCounts.keys.removeIf { it.first == userId && it.second != date }
            lastDateByUser[userId] = date
        }
        val key = userId to date
        val counter = dailyCounts.computeIfAbsent(key) { AtomicInteger(0) }
        counter.incrementAndGet()
    }
}
