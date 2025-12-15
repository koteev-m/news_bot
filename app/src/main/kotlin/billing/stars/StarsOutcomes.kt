package billing.stars

object StarsOutcomes {
    const val SUCCESS = "success"
    const val RATE_LIMITED = "rate_limited"
    const val SERVER = "server"
    const val BAD_REQUEST = "bad_request"
    const val DECODE_ERROR = "decode_error"
    const val OTHER = "other"
    const val STALE_RETURNED = "stale_returned"
}

object StarsPublicResults {
    const val OK = "ok"
    const val UNCONFIGURED = "unconfigured"
    const val TG_RATE_LIMITED = "tg_rate_limited"
    const val SERVER = "server"
    const val BAD_REQUEST = "bad_request"
    const val DECODE_ERROR = "decode_error"
    const val OTHER = "other"
}
