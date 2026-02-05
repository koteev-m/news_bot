package ab

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Serializable
data class Experiment(
    val key: String,
    val enabled: Boolean,
    val traffic: Map<String, Int>,
)

@Serializable
data class Assignment(
    val userId: Long,
    val key: String,
    val variant: String,
)

interface ExperimentsPort {
    suspend fun list(): List<Experiment>

    suspend fun upsert(e: Experiment)

    suspend fun getAssignment(
        userId: Long,
        key: String,
    ): Assignment?

    suspend fun saveAssignment(a: Assignment)
}

interface ExperimentsService {
    suspend fun assign(
        userId: Long,
        key: String,
    ): Assignment

    suspend fun activeAssignments(userId: Long): List<Assignment>
}

class ExperimentsServiceImpl(
    private val port: ExperimentsPort,
) : ExperimentsService {
    private fun pickDeterministic(
        userId: Long,
        experiment: Experiment,
    ): String {
        require(experiment.traffic.isNotEmpty()) { "experiment traffic must not be empty" }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("${experiment.key}:$userId".toByteArray(StandardCharsets.UTF_8))
        val bucket = (bytes[0].toInt() and 0xFF) % 100
        var cumulative = 0
        val entries = experiment.traffic.entries.sortedBy { it.key }
        val total = entries.sumOf { (_, percent) -> percent.coerceAtLeast(0) }
        if (total <= 0) {
            return entries.first().key
        }
        for ((variant, percent) in entries) {
            val clamped = percent.coerceAtLeast(0)
            if (clamped == 0) {
                continue
            }
            cumulative += clamped
            val scaledThreshold = (cumulative * 100) / total
            if (bucket < scaledThreshold) {
                return variant
            }
        }
        return entries.last().key
    }

    private suspend fun ensureAssignment(
        userId: Long,
        experiment: Experiment,
    ): Assignment {
        val existing = port.getAssignment(userId, experiment.key)
        if (existing != null) {
            return existing
        }
        val variant = pickDeterministic(userId, experiment)
        val assignment = Assignment(userId, experiment.key, variant)
        port.saveAssignment(assignment)
        return assignment
    }

    override suspend fun assign(
        userId: Long,
        key: String,
    ): Assignment {
        val experiment =
            port.list().firstOrNull { it.key == key && it.enabled }
                ?: throw NoSuchElementException("experiment disabled or missing")
        return ensureAssignment(userId, experiment)
    }

    override suspend fun activeAssignments(userId: Long): List<Assignment> {
        val experiments = port.list().filter { it.enabled }
        return experiments.map { experiment -> ensureAssignment(userId, experiment) }
    }
}
