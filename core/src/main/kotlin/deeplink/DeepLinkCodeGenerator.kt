package deeplink

import java.security.SecureRandom
import java.util.Base64

object DeepLinkCodeGenerator {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 12

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(byteLength: Int = 9): String {
        require(byteLength > 0) { "byteLength must be positive" }
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun isValid(value: String, minLength: Int = MIN_LENGTH, maxLength: Int = MAX_LENGTH): Boolean {
        if (value.length !in minLength..maxLength) {
            return false
        }
        return value.all { it.isAllowedChar() }
    }

    private fun Char.isAllowedChar(): Boolean {
        return this in 'a'..'z' ||
            this in 'A'..'Z' ||
            this in '0'..'9' ||
            this == '-' ||
            this == '_'
    }
}
