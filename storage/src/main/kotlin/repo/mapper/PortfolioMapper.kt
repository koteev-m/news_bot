package repo.mapper

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.model.NewPortfolio
import repo.model.PortfolioEntity
import repo.model.PortfolioUpdate
import repo.tables.PortfoliosTable

fun ResultRow.toPortfolioEntity(): PortfolioEntity = PortfolioEntity(
    portfolioId = this[PortfoliosTable.portfolioId],
    userId = this[PortfoliosTable.userId],
    name = this[PortfoliosTable.name],
    baseCurrency = this[PortfoliosTable.baseCurrency],
    isActive = this[PortfoliosTable.isActive],
    createdAt = this[PortfoliosTable.createdAt].toInstant(),
)

fun NewPortfolio.toColumnValues(): Map<Column<*>, Any?> = mapOf(
    PortfoliosTable.userId to userId,
    PortfoliosTable.name to name,
    PortfoliosTable.baseCurrency to baseCurrency,
    PortfoliosTable.isActive to isActive,
    PortfoliosTable.createdAt to createdAt.toDbTimestamp(),
)

fun PortfolioUpdate.toColumnValues(): Map<Column<*>, Any?> {
    val values = mutableMapOf<Column<*>, Any?>()
    name?.let { values[PortfoliosTable.name] = it }
    baseCurrency?.let { values[PortfoliosTable.baseCurrency] = it }
    isActive?.let { values[PortfoliosTable.isActive] = it }
    return values
}
