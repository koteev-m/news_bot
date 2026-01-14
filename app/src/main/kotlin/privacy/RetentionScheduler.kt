package privacy

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import common.runCatchingNonFatal

class RetentionScheduler(
    private val app: Application,
    private val service: PrivacyService,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = Duration.ofHours(24)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val now = Instant.now(clock)
                runCatchingNonFatal { service.runRetention(now) }
                delay(interval.toMillis())
            }
        }
        app.environment.monitor.subscribe(ApplicationStopping) { stop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
