package alerts

/** Matrix of base thresholds per signal class. */
data class ThresholdMatrix(val entries: Map<String, Thresholds>) {
    fun getThreshold(classId: String, window: String): Double? {
        val thresholds = entries[classId] ?: return null
        return when (window.lowercase()) {
            "fast" -> thresholds.fast
            else -> thresholds.daily
        }
    }
}

data class Thresholds(val fast: Double, val daily: Double)

internal fun proMultiplier(atr: Double?, sigma: Double?): Double {
    if (atr == null || sigma == null || sigma == 0.0) return 1.0
    val ratio = atr / sigma
    return ratio.coerceIn(0.7, 1.3)
}
