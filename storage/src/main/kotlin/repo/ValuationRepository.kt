package repo

import db.DatabaseFactory.dbQuery
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import portfolio.model.DateRange
import repo.mapper.setValues
import repo.mapper.toColumnValues
import repo.mapper.toValuationDailyRecord
import repo.model.NewValuationDaily
import repo.model.ValuationDailyRecord
import repo.tables.ValuationsDailyTable

class ValuationRepository {
    suspend fun upsert(record: NewValuationDaily): ValuationDailyRecord = dbQuery {
        val predicate = (ValuationsDailyTable.portfolioId eq record.portfolioId) and
            (ValuationsDailyTable.date eq record.date)
        val updated = ValuationsDailyTable.update({ predicate }) {
            it.setValues(record.toColumnValues())
        }
        if (updated == 0) {
            ValuationsDailyTable.insert {
                it.setValues(record.toColumnValues())
            }
        }
        ValuationsDailyTable.select { predicate }.single().toValuationDailyRecord()
    }

    suspend fun find(portfolioId: UUID, date: LocalDate): ValuationDailyRecord? = dbQuery {
        ValuationsDailyTable.select {
            (ValuationsDailyTable.portfolioId eq portfolioId) and (ValuationsDailyTable.date eq date)
        }.singleOrNull()?.toValuationDailyRecord()
    }

    suspend fun list(portfolioId: UUID, limit: Int, offset: Long = 0): List<ValuationDailyRecord> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        ValuationsDailyTable.select { ValuationsDailyTable.portfolioId eq portfolioId }
            .orderBy(ValuationsDailyTable.date, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toValuationDailyRecord() }
    }

    suspend fun listRange(
        portfolioId: UUID,
        range: DateRange,
        limit: Int,
        offset: Long = 0,
    ): List<ValuationDailyRecord> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        ValuationsDailyTable.select {
            (ValuationsDailyTable.portfolioId eq portfolioId) and
                (ValuationsDailyTable.date greaterEq range.from) and
                (ValuationsDailyTable.date lessEq range.to)
        }
            .orderBy(ValuationsDailyTable.date, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toValuationDailyRecord() }
    }

    suspend fun delete(portfolioId: UUID, date: LocalDate): Boolean = dbQuery {
        ValuationsDailyTable.deleteWhere {
            (ValuationsDailyTable.portfolioId eq portfolioId) and (ValuationsDailyTable.date eq date)
        } > 0
    }
}
