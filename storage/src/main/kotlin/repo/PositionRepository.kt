package repo

import db.DatabaseFactory.dbQuery
import java.util.UUID
import model.PositionDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import repo.mapper.setValues
import repo.mapper.toInsertValues
import repo.mapper.toPositionDto
import repo.mapper.toUpdateValues
import repo.tables.PositionsTable

class PositionRepository {
    suspend fun save(position: PositionDto): PositionDto = dbQuery {
        val predicate = (PositionsTable.portfolioId eq position.portfolioId) and
            (PositionsTable.instrumentId eq position.instrumentId)
        val updated = PositionsTable.update({ predicate }) {
            it.setValues(position.toUpdateValues())
        }
        if (updated == 0) {
            PositionsTable.insert {
                it.setValues(position.toInsertValues())
            }
        }
        PositionsTable
            .selectAll()
            .where { predicate }
            .single()
            .toPositionDto()
    }

    suspend fun find(portfolioId: UUID, instrumentId: Long): PositionDto? = dbQuery {
        val predicate = (PositionsTable.portfolioId eq portfolioId) and (PositionsTable.instrumentId eq instrumentId)
        PositionsTable
            .selectAll()
            .where { predicate }
            .singleOrNull()
            ?.toPositionDto()
    }

    suspend fun list(portfolioId: UUID, limit: Int, offset: Long = 0): List<PositionDto> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        PositionsTable
            .selectAll()
            .where { PositionsTable.portfolioId eq portfolioId }
            .orderBy(PositionsTable.instrumentId, SortOrder.ASC)
            .limit(limit, offset)
            .map { it.toPositionDto() }
    }

    suspend fun delete(portfolioId: UUID, instrumentId: Long): Boolean = dbQuery {
        PositionsTable.deleteWhere {
            (PositionsTable.portfolioId eq portfolioId) and (PositionsTable.instrumentId eq instrumentId)
        } > 0
    }
}
