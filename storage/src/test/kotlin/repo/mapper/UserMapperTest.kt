package repo.mapper

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import repo.model.NewUser
import repo.tables.UsersTable

class UserMapperTest {
    @Test
    fun `maps result row to user entity`() {
        val createdAt = Instant.parse("2024-03-01T10:15:30Z")
        val row = testResultRow(
            UsersTable.userId to 1L,
            UsersTable.tgUserId to 42L,
            UsersTable.createdAt to OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        )

        val entity = row.toUserEntity()

        assertEquals(1L, entity.userId)
        assertEquals(42L, entity.telegramUserId)
        assertEquals(createdAt, entity.createdAt)
    }

    @Test
    fun `maps new user to column values`() {
        val createdAt = Instant.parse("2024-03-01T10:15:30Z")
        val values = NewUser(telegramUserId = null, createdAt = createdAt).toColumnValues()

        assertEquals(null, values[UsersTable.tgUserId])
        assertEquals(OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), values[UsersTable.createdAt])
    }
}
