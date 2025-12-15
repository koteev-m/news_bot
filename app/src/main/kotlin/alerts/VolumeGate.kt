package alerts

/** Volume gating helper. */
data class VolumeGate(val k: Double) {
    fun allows(volume: Double?, avgVolume: Double?): Boolean {
        if (volume == null || avgVolume == null || avgVolume <= 0.0) return true
        return volume >= k * avgVolume
    }
}
