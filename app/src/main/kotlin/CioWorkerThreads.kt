package app

import com.typesafe.config.ConfigFactory
import common.runCatchingNonFatal

@Suppress("unused")
private val configuredCioWorkerThreads: Int = configureCioWorkerThreads()

private fun configureCioWorkerThreads(): Int {
    val existing = System.getProperty("ktor.server.cio.workerCount")?.toIntOrNull()
    if (existing != null && existing > 0) {
        return existing
    }
    val config = ConfigFactory.load()
    val fromConfig = runCatchingNonFatal { config.getInt("performance.workerThreads") }.getOrNull()
    val resolved = (fromConfig ?: Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    System.setProperty("ktor.server.cio.workerCount", resolved.toString())
    return resolved
}
