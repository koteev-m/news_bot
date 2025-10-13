package growth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class TelegramMessenger(private val http: HttpClient, private val botToken: String) : Messenger {
    override suspend fun sendTelegram(userId: Long, text: String, locale: String): Boolean {
        val url = "https://api.telegram.org/bot${botToken}/sendMessage"
        val resp = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(mapOf("chat_id" to userId, "text" to text))
        }
        return resp.status.isSuccess()
    }
}
