package netflow2

import java.util.Locale

fun normalizeTicker(raw: String): String {
    val normalized = raw.trim().uppercase(Locale.ROOT)
    require(normalized.isNotEmpty()) { "ticker must not be blank" }
    require(normalized.none { it.isWhitespace() }) { "ticker must not contain whitespace" }
    require(ALLOWED_CHARACTERS.matches(normalized)) { "ticker has invalid characters" }
    return normalized
}

private val ALLOWED_CHARACTERS = Regex("^[A-Z0-9._-]+$")
