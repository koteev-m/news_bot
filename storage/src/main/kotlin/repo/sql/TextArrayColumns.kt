package repo.sql

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import java.sql.Array as JdbcArray

private class TextArrayColumnType : ColumnType<List<String>>() {
    override fun sqlType(): String = "TEXT[]"

    override fun valueFromDB(value: Any): List<String> = when (value) {
        is Collection<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        is JdbcArray -> value.useArray { parseFromAny(it) }
        is String -> value.parseFromString()
        else -> error("Unexpected value for TEXT[]: ${value::class}")
    }

    override fun notNullValueToDB(value: List<String>): Any = value.toTypedArray()

    override fun nonNullValueToString(value: List<String>): String =
        value.joinToString(prefix = "{", postfix = "}") { it }

    private fun String.parseFromString(): List<String> {
        val raw = trim('{', '}')
        if (raw.isBlank()) return emptyList()
        return raw.split(',').map { it.trim().trim('"') }
    }

    private fun parseFromAny(value: Any): List<String> = when (value) {
        is Array<*> -> value.filterIsInstance<String>()
        is Collection<*> -> value.filterIsInstance<String>()
        is String -> value.parseFromString()
        else -> emptyList()
    }

    private inline fun <T> JdbcArray.useArray(block: (Any) -> T): T = try {
        block(array)
    } finally {
        free()
    }
}

fun Table.textArray(name: String): Column<List<String>> = registerColumn(name, TextArrayColumnType())
