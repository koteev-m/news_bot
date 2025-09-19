package repo.mapper

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.model.NewValuationDaily
import repo.model.ValuationDailyRecord
import repo.tables.ValuationsDailyTable

fun ResultRow.toValuationDailyRecord(): ValuationDailyRecord = ValuationDailyRecord(
    portfolioId = this[ValuationsDailyTable.portfolioId],
    date = this[ValuationsDailyTable.date],
    valueRub = this[ValuationsDailyTable.valueRub],
    pnlDay = this[ValuationsDailyTable.pnlDay],
    pnlTotal = this[ValuationsDailyTable.pnlTotal],
    drawdown = this[ValuationsDailyTable.drawdown],
)

fun NewValuationDaily.toColumnValues(): Map<Column<*>, Any?> = mapOf(
    ValuationsDailyTable.portfolioId to portfolioId,
    ValuationsDailyTable.date to date,
    ValuationsDailyTable.valueRub to valueRub,
    ValuationsDailyTable.pnlDay to pnlDay,
    ValuationsDailyTable.pnlTotal to pnlTotal,
    ValuationsDailyTable.drawdown to drawdown,
)
