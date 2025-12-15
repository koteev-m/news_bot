package billing.stars

object StarsMetrics {
    const val TIMER_BOT = "stars_bot_balance_fetch_seconds"
    const val TIMER_LEGACY = "stars_balance_fetch_seconds"
    const val TIMER_ADMIN = "stars_admin_bot_balance_request_seconds"
    const val TIMER_PUBLIC = "stars_public_bot_balance_request_seconds"
    const val CNT_OUTCOME = "stars_bot_balance_fetch_total"
    const val CNT_CACHE = "stars_bot_balance_cache_total"
    const val CNT_ADMIN_REQUESTS = "stars_admin_bot_balance_requests_total"
    const val CNT_PUBLIC_REQUESTS = "stars_public_bot_balance_requests_total"
    const val CNT_BOUNDED_STALE = "stars_bot_balance_bounded_stale_total"
    const val LABEL_OUTCOME = "outcome"
    const val LABEL_STATE = "state"
    const val LABEL_RESULT = "result"
    const val LABEL_REASON = "reason"
    const val GAUGE_CACHE_AGE = "stars_bot_balance_cache_age_seconds"
    const val GAUGE_CACHE_TTL = "stars_bot_balance_cache_ttl_seconds"
    const val GAUGE_RATE_LIMIT_REMAINING = "stars_bot_balance_rl_window_remaining_seconds"
}
