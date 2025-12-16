package alerts

object AlertDeliveryReasons {
    const val DIRECT = "direct"
    const val QUIET_HOURS_FLUSH = "quiet_hours_flush"
    const val PORTFOLIO_SUMMARY = "portfolio_summary"
}

object AlertSuppressionReasons {
    const val BUDGET = "budget"
    const val COOLDOWN = "cooldown"
    const val QUIET_HOURS = "quiet_hours"
    const val DUPLICATE = "duplicate"
    const val NO_VOLUME = "no_volume"
    const val BELOW_THRESHOLD = "below_threshold"
}
