package alerts

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class AlertsRepositoryMemory : AlertsRepository {
    private val states = ConcurrentHashMap<Long, FsmState>()
    private val dailyCounts = ConcurrentHashMap<Long, MutableMap<LocalDate, Int>>()

    override fun getState(userId: Long): FsmState = states[userId] ?: FsmState.IDLE

    override fun setState(userId: Long, state: FsmState) {
        states[userId] = state
    }

    override fun getDailyPushCount(userId: Long, date: LocalDate): Int {
        val map = dailyCounts[userId] ?: return 0
        return map[date] ?: 0
    }

    override fun incDailyPushCount(userId: Long, date: LocalDate) {
        dailyCounts.compute(userId) { _, existing ->
            val map = existing ?: mutableMapOf()
            map.keys.filter { it != date }.forEach { map.remove(it) }
            map[date] = (map[date] ?: 0) + 1
            map
        }
    }
}
