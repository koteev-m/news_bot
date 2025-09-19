package repo.model

import java.time.Instant

/** Represents a persisted application user. */
data class UserEntity(
    val userId: Long,
    val telegramUserId: Long?,
    val createdAt: Instant,
)

/** Payload for creating a user row. */
data class NewUser(
    val telegramUserId: Long?,
    val createdAt: Instant = Instant.now(),
)
