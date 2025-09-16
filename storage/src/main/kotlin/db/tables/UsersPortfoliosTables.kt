package db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.CustomFunction
import java.util.UUID

private fun genRandomUuid() = CustomFunction<UUID>("gen_random_uuid", org.jetbrains.exposed.sql.UUIDColumnType())

object UsersTable : Table("users") {
    val userId = long("user_id").autoIncrement()
    val tgUserId = long("tg_user_id").nullable().uniqueIndex()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(userId)
}

object PortfoliosTable : Table("portfolios") {
    val portfolioId = uuid("portfolio_id").defaultExpression(genRandomUuid())
    val userId = long("user_id").references(UsersTable.userId, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val baseCurrency = char("base_currency", 3)
    val isActive = bool("is_active").default(true)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(portfolioId)
    init {
        uniqueIndex("uk_portfolios_user_name", userId, name)
    }
}
