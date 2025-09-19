package repo.mapper

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.statements.UpdateBuilder

internal fun Instant.toDbTimestamp(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

@Suppress("UNCHECKED_CAST")
internal fun UpdateBuilder<*>.setValues(values: Map<Column<*>, Any?>) {
    values.forEach { (column, value) ->
        this[column as Column<Any?>] = value
    }
}
