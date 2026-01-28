package news.moderation

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

object ModerationHashes {
    fun hashEntity(entity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(entity.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf(16))
    }
}
