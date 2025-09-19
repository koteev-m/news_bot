package repo.mapper

import model.PositionDto
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.tables.PositionsTable

fun ResultRow.toPositionDto(): PositionDto = PositionDto(
    portfolioId = this[PositionsTable.portfolioId],
    instrumentId = this[PositionsTable.instrumentId],
    qty = this[PositionsTable.qty],
    avgPrice = this[PositionsTable.avgPrice],
    avgPriceCcy = this[PositionsTable.avgPriceCcy],
    updatedAt = this[PositionsTable.updatedAt].toInstant(),
)

fun PositionDto.toInsertValues(): Map<Column<*>, Any?> = mapOf(
    PositionsTable.portfolioId to portfolioId,
    PositionsTable.instrumentId to instrumentId,
    PositionsTable.qty to qty,
    PositionsTable.avgPrice to avgPrice,
    PositionsTable.avgPriceCcy to avgPriceCcy,
    PositionsTable.updatedAt to updatedAt.toDbTimestamp(),
)

fun PositionDto.toUpdateValues(): Map<Column<*>, Any?> = mapOf(
    PositionsTable.qty to qty,
    PositionsTable.avgPrice to avgPrice,
    PositionsTable.avgPriceCcy to avgPriceCcy,
    PositionsTable.updatedAt to updatedAt.toDbTimestamp(),
)
