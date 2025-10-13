package crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class Envelope(
    val alg: String = "AES/GCM/NoPadding",
    val kid: String,
    val iv: String,
    val ct: String,
    val tagLen: Int = 128,
    val dekWrapped: String
)

interface KmsAdapter {
    suspend fun wrapDek(kid: String, rawKey: ByteArray): ByteArray
    suspend fun unwrapDek(kid: String, wrapped: ByteArray): ByteArray
}

object EnvelopeCrypto {
    private val json = Json { encodeDefaults = true }
    private val rnd = SecureRandom()

    fun generateDek(): ByteArray {
        val key = ByteArray(32)
        rnd.nextBytes(key)
        return key
    }

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray, tagLen: Int): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(tagLen, iv))
        return c
    }

    suspend fun encrypt(kms: KmsAdapter, kid: String, plaintext: ByteArray): String {
        val dek = generateDek()
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val sk = SecretKeySpec(dek, "AES")
        val c = cipher(Cipher.ENCRYPT_MODE, sk, iv, 128)
        val ct = c.doFinal(plaintext)
        val wrapped = kms.wrapDek(kid, dek)
        val env = Envelope(
            kid = kid,
            iv = Base64.getEncoder().encodeToString(iv),
            ct = Base64.getEncoder().encodeToString(ct),
            dekWrapped = Base64.getEncoder().encodeToString(wrapped)
        )
        return json.encodeToString(Envelope.serializer(), env)
    }

    suspend fun decrypt(kms: KmsAdapter, envelopeJson: String): ByteArray {
        val env = json.decodeFromString(Envelope.serializer(), envelopeJson)
        val iv = Base64.getDecoder().decode(env.iv)
        val ct = Base64.getDecoder().decode(env.ct)
        val wrapped = Base64.getDecoder().decode(env.dekWrapped)
        val dek = kms.unwrapDek(env.kid, wrapped)
        val sk = SecretKeySpec(dek, "AES")
        val c = cipher(Cipher.DECRYPT_MODE, sk, iv, env.tagLen)
        return c.doFinal(ct)
    }
}
