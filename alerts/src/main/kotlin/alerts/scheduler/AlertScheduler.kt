package alerts.scheduler

import alerts.engine.AlertEngine
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

public class AlertScheduler(
    private val engine: AlertEngine,
    private val scope: CoroutineScope,
    private val clock: Clock
) {
    public data class Plan(val fastEvery: Duration, val dayEvery: Duration)

    public fun start(instruments: List<Long>, portfolios: List<UUID>, plan: Plan): Job {
        return scope.launch {
            val fastJob = launch { runFastLoop(instruments, plan.fastEvery) }
            val dayJob = launch { runDayLoop(instruments, portfolios, plan.dayEvery) }
            try {
                fastJob.join()
                dayJob.join()
            } finally {
                fastJob.cancel()
                dayJob.cancel()
            }
        }
    }

    private suspend fun runFastLoop(instruments: List<Long>, period: Duration) {
        if (instruments.isEmpty()) {
            return
        }
        var index = 0
        while (scope.isActive) {
            val id = instruments[index % instruments.size]
            engine.checkInstrument(id)
            index += 1
            clock.instant()
            delay(period.toMillis())
        }
    }

    private suspend fun runDayLoop(instruments: List<Long>, portfolios: List<UUID>, period: Duration) {
        var index = 0
        while (scope.isActive) {
            if (instruments.isNotEmpty()) {
                val id = instruments[index % instruments.size]
                engine.checkInstrument(id)
                index += 1
            }
            for (portfolioId in portfolios) {
                engine.checkPortfolio(portfolioId)
            }
            clock.instant()
            delay(period.toMillis())
        }
    }
}
