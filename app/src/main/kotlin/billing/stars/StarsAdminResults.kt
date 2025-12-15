package billing.stars

object StarsAdminResults {
    const val OK = "ok"
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN = "forbidden"
    const val UNCONFIGURED = "unconfigured"
    const val LOCAL_RATE_LIMITED = "local_rate_limited"
    const val TG_RATE_LIMITED = "tg_rate_limited"
    const val SERVER = "server"
    const val BAD_REQUEST = "bad_request"
    const val DECODE_ERROR = "decode_error"
    const val OTHER = "other"
}
