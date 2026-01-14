package auth

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import common.runCatchingNonFatal

object WebAppVerify {
    private const val HASH_KEY = "hash"
    private const val AUTH_DATE_KEY = "auth_date"
    private val WEB_APP_KEY_BYTES = "WebAppData".toByteArray(StandardCharsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }

    data class Parsed(
        val raw: Map<String, String>,
        val userId: Long?,
        val username: String?,
        val firstName: String?,
        val authDate: Instant,
        val hash: String,
    )

    fun parse(initData: String): Parsed {
        val trimmed = initData.trim().removePrefix("?")
        require(trimmed.isNotEmpty()) { "initData must not be blank" }

        val pairs = trimmed.split('&')
            .filter { it.isNotEmpty() }

        val rawMap = LinkedHashMap<String, String>(pairs.size)
        for (pair in pairs) {
            val separatorIndex = pair.indexOf('=')
            val keyEncoded: String
            val valueEncoded: String
            if (separatorIndex >= 0) {
                keyEncoded = pair.substring(0, separatorIndex)
                valueEncoded = pair.substring(separatorIndex + 1)
            } else {
                keyEncoded = pair
                valueEncoded = ""
            }
            val key = urlDecode(keyEncoded)
            val value = urlDecode(valueEncoded)
            rawMap[key] = value
        }

        val hash = rawMap[HASH_KEY]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("hash is missing")

        val authDateValue = rawMap[AUTH_DATE_KEY]?.toLongOrNull()
            ?: throw IllegalArgumentException("auth_date is missing or invalid")
        val authDate = Instant.ofEpochSecond(authDateValue)

        val userRaw = rawMap["user"]
        var userId: Long? = null
        var username: String? = null
        var firstName: String? = null
        if (!userRaw.isNullOrBlank()) {
            runCatchingNonFatal { json.parseToJsonElement(userRaw) }
                .map { it.jsonObject }
                .onSuccess { userObject ->
                    userId = userObject["id"]?.jsonPrimitive?.longOrNull
                    username = userObject["username"]?.jsonPrimitive?.contentOrNull
                    firstName = userObject["first_name"]?.jsonPrimitive?.contentOrNull
                }
        }

        return Parsed(
            raw = rawMap.toMap(),
            userId = userId,
            username = username,
            firstName = firstName,
            authDate = authDate,
            hash = hash,
        )
    }

    fun isValid(
        initData: String,
        botToken: String,
        ttlMinutes: Int,
        now: Instant = Instant.now(),
    ): Boolean {
        val parsed = runCatchingNonFatal { parse(initData) }.getOrElse { return false }
        return isValid(parsed, botToken, ttlMinutes, now)
    }

    fun isValid(
        parsed: Parsed,
        botToken: String,
        ttlMinutes: Int,
        now: Instant = Instant.now(),
    ): Boolean {
        if (botToken.isBlank() || ttlMinutes <= 0) {
            return false
        }

        val dataCheckString = buildDataCheckString(parsed.raw)
        val secret = hmacSha256(WEB_APP_KEY_BYTES, botToken.toByteArray(StandardCharsets.UTF_8))
        val calcHash = hmacSha256(secret, dataCheckString.toByteArray(StandardCharsets.UTF_8)).toHex()

        if (!constantTimeEquals(calcHash, parsed.hash)) {
            return false
        }

        val ttlDuration = Duration.ofMinutes(ttlMinutes.toLong())
        if (parsed.authDate.isBefore(now.minus(ttlDuration))) {
            return false
        }

        return true
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.toByteArray(StandardCharsets.UTF_8)
        return java.security.MessageDigest.isEqual(aBytes, bBytes)
    }

    private fun buildDataCheckString(raw: Map<String, String>): String =
        raw.filterKeys { it != HASH_KEY }
            .toSortedMap()
            .entries
            .joinToString(separator = "\n") { (key, value) -> "$key=$value" }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        val unsigned = byte.toInt() and 0xff
        unsigned.toString(16).padStart(2, '0')
    }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
