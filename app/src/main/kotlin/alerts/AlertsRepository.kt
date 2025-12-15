package alerts

import java.time.LocalDate

interface AlertsRepository {
    fun getState(userId: Long): FsmState
    fun setState(userId: Long, state: FsmState)
    fun getDailyPushCount(userId: Long, date: LocalDate): Int
    fun incDailyPushCount(userId: Long, date: LocalDate)
}
