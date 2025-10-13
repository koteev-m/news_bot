package kms

import crypto.KmsAdapter
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

class VaultKmsAdapter(
    private val http: HttpClient,
    private val baseUrl: String,
    private val token: String
) : KmsAdapter {

    override suspend fun wrapDek(kid: String, rawKey: ByteArray): ByteArray {
        val url = "${baseUrl}/encrypt/${kid}"
        val body = """{"plaintext":"${Base64.getEncoder().encodeToString(rawKey)}"}"""
        val resp: HttpResponse = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val txt = resp.bodyAsText()
        val json = Json.parseToJsonElement(txt).jsonObject
        val ct = json["data"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content
        return ct.toByteArray()
    }

    override suspend fun unwrapDek(kid: String, wrapped: ByteArray): ByteArray {
        val url = "${baseUrl}/decrypt/${kid}"
        val body = """{"ciphertext":"${String(wrapped)}"}"""
        val resp: HttpResponse = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val txt = resp.bodyAsText()
        val json = Json.parseToJsonElement(txt).jsonObject
        val ptb64 = json["data"]!!.jsonObject["plaintext"]!!.jsonPrimitive.content
        return Base64.getDecoder().decode(ptb64)
    }
}
