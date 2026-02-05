package repo.mapper

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.ResultRow
import java.util.LinkedHashMap

private val testDatabase by lazy {
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
}

internal fun testResultRow(vararg pairs: Pair<Expression<*>, Any?>): ResultRow {
    testDatabase
    val fieldIndex = LinkedHashMap<Expression<*>, Int>(pairs.size)
    val data = arrayOfNulls<Any?>(pairs.size)
    pairs.forEachIndexed { index, (expression, value) ->
        fieldIndex[expression] = index
        data[index] = value
    }
    return ResultRow(fieldIndex, data)
}
