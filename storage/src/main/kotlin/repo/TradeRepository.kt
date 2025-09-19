package repo

import db.DatabaseFactory.dbQuery
import java.time.Instant
import java.util.UUID
import model.TradeDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.between
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import repo.mapper.setValues
import repo.mapper.toColumnValues
import repo.mapper.toDbTimestamp
import repo.mapper.toTradeDto
import repo.model.NewTrade
import repo.model.TradeUpdate
import repo.tables.TradesTable

class TradeRepository {
    suspend fun createTrade(newTrade: NewTrade): TradeDto = dbQuery {
        val statement = TradesTable.insert {
            it.setValues(newTrade.toColumnValues())
        }
        statement.resultedValues?.singleOrNull()?.toTradeDto()
            ?: error("Failed to insert trade")
    }

    suspend fun updateTrade(tradeId: Long, update: TradeUpdate): TradeDto? = dbQuery {
        val values = update.toColumnValues()
        if (values.isEmpty()) {
            return@dbQuery findById(tradeId)
        }
        val updated = TradesTable.update({ TradesTable.tradeId eq tradeId }) {
            it.setValues(values)
        }
        if (updated > 0) {
            TradesTable.select { TradesTable.tradeId eq tradeId }
                .singleOrNull()?.toTradeDto()
        } else {
            null
        }
    }

    suspend fun deleteTrade(tradeId: Long): Boolean = dbQuery {
        TradesTable.deleteWhere { TradesTable.tradeId eq tradeId } > 0
    }

    suspend fun findById(tradeId: Long): TradeDto? = dbQuery {
        TradesTable.select { TradesTable.tradeId eq tradeId }
            .singleOrNull()?.toTradeDto()
    }

    suspend fun findByExternalId(extId: String): TradeDto? = dbQuery {
        TradesTable.select { TradesTable.extId eq extId }
            .singleOrNull()?.toTradeDto()
    }

    suspend fun listByPortfolio(portfolioId: UUID, limit: Int, offset: Long = 0): List<TradeDto> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        TradesTable.select { TradesTable.portfolioId eq portfolioId }
            .orderBy(TradesTable.datetime, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toTradeDto() }
    }

    suspend fun listByInstrument(instrumentId: Long, limit: Int, offset: Long = 0): List<TradeDto> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        TradesTable.select { TradesTable.instrumentId eq instrumentId }
            .orderBy(TradesTable.datetime, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toTradeDto() }
    }

    suspend fun listByPeriod(
        portfolioId: UUID,
        from: Instant?,
        to: Instant?,
        limit: Int,
        offset: Long = 0,
    ): List<TradeDto> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        val condition = when {
            from != null && to != null -> TradesTable.datetime.between(from.toDbTimestamp(), to.toDbTimestamp())
            from != null -> TradesTable.datetime greaterEq from.toDbTimestamp()
            to != null -> TradesTable.datetime lessEq to.toDbTimestamp()
            else -> null
        }
        val op = condition?.let { (TradesTable.portfolioId eq portfolioId) and it } ?: (TradesTable.portfolioId eq portfolioId)
        TradesTable.select { op }
            .orderBy(TradesTable.datetime, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toTradeDto() }
    }
}
