package news.moderation

import java.nio.charset.StandardCharsets
import java.util.UUID

object ModerationIds {
    fun clusterIdFromKey(clusterKey: String): UUID {
        return UUID.nameUUIDFromBytes(clusterKey.toByteArray(StandardCharsets.UTF_8))
    }
}
