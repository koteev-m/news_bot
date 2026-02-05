package routes

/**
 * Checks whether the provided [ifNoneMatch] header value matches the given strong [etag].
 *
 * Supports wildcard matching ("*"), weak ETags (W/), quoted values, and comma-separated lists
 * in accordance with RFC 7232 section 3.2.
 */
fun matchesEtag(
    ifNoneMatch: String?,
    etag: String,
): Boolean {
    if (ifNoneMatch.isNullOrBlank()) return false

    return ifNoneMatch
        .split(',')
        .map { it.trim() }
        .any { candidate ->
            val token = candidate.trim()
            if (token == "*") return true

            val weakStripped = token.removePrefix("W/").removePrefix("w/").trim()
            val unquoted = weakStripped.trim('"')
            if (unquoted == "*") return true

            unquoted == etag
        }
}
