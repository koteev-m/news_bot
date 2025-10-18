package repo

import db.DatabaseFactory.dbQuery
import java.sql.Array as JdbcArray
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import residency.ResidencyPolicy

private class TextArrayColumnType : ColumnType() {
    override fun sqlType(): String = "TEXT[]"

    override fun valueFromDB(value: Any): Any = when (value) {
        is Collection<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        is JdbcArray -> {
            val array = value.array
            try {
                when (array) {
                    is Array<*> -> array.filterIsInstance<String>()
                    is Collection<*> -> array.filterIsInstance<String>()
                    else -> value.toString().parse()
                }
            } finally {
                value.free()
            }
        }
        is String -> value.parse()
        else -> error("Unexpected value for TEXT[]: ${value::class}")
    }

    override fun notNullValueToDB(value: Any): Any = when (value) {
        is Collection<*> -> TransactionManager.current().connection.createArrayOf("text", value.toTypedArray())
        is Array<*> -> TransactionManager.current().connection.createArrayOf("text", value)
        else -> value
    }

    override fun nonNullValueToString(value: Any): String = when (value) {
        is Collection<*> -> value.joinToString(prefix = "{", postfix = "}") { it.toString() }
        is Array<*> -> value.joinToString(prefix = "{", postfix = "}") { it.toString() }
        else -> super.nonNullValueToString(value)
    }

    private fun Any.parse(): List<String> {
        val raw = toString().trim('{', '}')
        if (raw.isBlank()) return emptyList()
        return raw.split(',').map { it.trim().trim('"') }
    }
}

private fun Table.textArray(name: String): Column<List<String>> = registerColumn(name, TextArrayColumnType())

object ResidencyPolicies : Table("data_residency_policies") {
    val tenantId = long("tenant_id")
    val region = text("region")
    val dataClasses = textArray("data_classes")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId)
}

class ResidencyRepository {
    suspend fun getPolicy(tenantId: Long): ResidencyPolicy? = dbQuery {
        ResidencyPolicies
            .select { ResidencyPolicies.tenantId eq tenantId }
            .firstOrNull()
            ?.let {
                ResidencyPolicy(
                    tenantId = it[ResidencyPolicies.tenantId],
                    region = it[ResidencyPolicies.region],
                    dataClasses = it[ResidencyPolicies.dataClasses]
                )
            }
    }
}
