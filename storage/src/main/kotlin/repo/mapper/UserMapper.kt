package repo.mapper

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.model.NewUser
import repo.model.UserEntity
import repo.tables.UsersTable

fun ResultRow.toUserEntity(): UserEntity = UserEntity(
    userId = this[UsersTable.userId],
    telegramUserId = this[UsersTable.tgUserId],
    createdAt = this[UsersTable.createdAt].toInstant(),
)

fun NewUser.toColumnValues(): Map<Column<*>, Any?> = mapOf(
    UsersTable.tgUserId to telegramUserId,
    UsersTable.createdAt to createdAt.toDbTimestamp(),
)
