package auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAppVerifyTest {
    private val botToken = "1234567890:ABCdefGhIjklMNopQR_stUvWXyz1234"
    private val verificationNow = Instant.parse("2024-01-01T12:00:00Z")
    private val freshAuthDate = verificationNow.minusSeconds(120)
    private val baseParameters = mapOf(
        "auth_date" to freshAuthDate.epochSecond.toString(),
        "query_id" to "AAEAAABBBB",
        "user" to """{"id":987654321,"username":"john","first_name":"John"}""",
        "extra" to "value with spaces",
    )

    @Test
    fun `isValid should return true for correctly signed initData`() {
        val (initData, _) = createSignedInitData(
            parameters = baseParameters,
            botToken = botToken,
            order = listOf("user", "auth_date", "extra", "query_id", "hash"),
        )

        assertTrue(WebAppVerify.isValid(initData, botToken, ttlMinutes = 30, now = verificationNow))

        val parsed = WebAppVerify.parse(initData)
        assertEquals(987654321L, parsed.userId)
        assertEquals("john", parsed.username)
        assertEquals("John", parsed.firstName)
        assertEquals(freshAuthDate, parsed.authDate)
    }

    @Test
    fun `isValid should fail when payload is tampered`() {
        val (validInitData, withHash) = createSignedInitData(
            parameters = baseParameters,
            botToken = botToken,
        )
        val tamperedUser = """{"id":987654321,"username":"attacker","first_name":"John"}"""
        val encodedOriginalUser = URLEncoder.encode(baseParameters.getValue("user"), StandardCharsets.UTF_8)
        val encodedTamperedUser = URLEncoder.encode(tamperedUser, StandardCharsets.UTF_8)
        val tamperedInitData = validInitData.replace(encodedOriginalUser, encodedTamperedUser)

        assertFalse(WebAppVerify.isValid(tamperedInitData, botToken, ttlMinutes = 30, now = verificationNow))
        assertTrue(WebAppVerify.constantTimeEquals(withHash.getValue("hash"), withHash.getValue("hash")))
    }

    @Test
    fun `isValid should fail when auth date exceeds ttl`() {
        val expiredAuthDate = verificationNow.minusSeconds(3600)
        val expiredParameters = baseParameters.toMutableMap()
        expiredParameters["auth_date"] = expiredAuthDate.epochSecond.toString()
        val (expiredInitData, _) = createSignedInitData(expiredParameters, botToken)

        assertFalse(WebAppVerify.isValid(expiredInitData, botToken, ttlMinutes = 15, now = verificationNow))
    }

    @Test
    fun `isValid should ignore ordering and percent-encoding variations`() {
        val (orderedInitData, _) = createSignedInitData(
            parameters = baseParameters,
            botToken = botToken,
            order = listOf("hash", "query_id", "extra", "auth_date", "user"),
        )
        val reencodedInitData = orderedInitData.replace("value+with+spaces", "value%20with%20spaces")

        assertTrue(WebAppVerify.isValid(reencodedInitData, botToken, ttlMinutes = 30, now = verificationNow))
    }

    private fun createSignedInitData(
        parameters: Map<String, String>,
        botToken: String,
        order: List<String>? = null,
    ): Pair<String, Map<String, String>> {
        val dataCheckString = parameters.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "$key=$value" }
        val secret = hmacSha256(
            "WebAppData".toByteArray(StandardCharsets.UTF_8),
            botToken.toByteArray(StandardCharsets.UTF_8)
        )
        val hash = hmacSha256(secret, dataCheckString.toByteArray(StandardCharsets.UTF_8)).toHex()

        val withHash = LinkedHashMap(parameters)
        withHash["hash"] = hash
        val desiredOrder = order ?: withHash.keys.toList()
        val finalOrder = if (desiredOrder.size == withHash.size) {
            desiredOrder
        } else {
            desiredOrder + withHash.keys.filter { it !in desiredOrder }
        }
        val initData = finalOrder.joinToString("&") { key ->
            val value = withHash.getValue(key)
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8)
            "$encodedKey=$encodedValue"
        }
        return initData to withHash
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        val unsigned = byte.toInt() and 0xff
        unsigned.toString(16).padStart(2, '0')
    }
}
