package repo

import db.DatabaseFactory.dbQuery
import java.util.UUID
import org.jetbrains.exposed.sql.LowerCase
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import repo.mapper.setValues
import repo.mapper.toColumnValues
import repo.mapper.toPortfolioEntity
import repo.model.NewPortfolio
import repo.model.PortfolioEntity
import repo.model.PortfolioUpdate
import repo.tables.PortfoliosTable

class PortfolioRepository {
    suspend fun create(newPortfolio: NewPortfolio): PortfolioEntity = dbQuery {
        val statement = PortfoliosTable.insert {
            it.setValues(newPortfolio.toColumnValues())
        }
        statement.resultedValues?.singleOrNull()?.toPortfolioEntity()
            ?: error("Failed to insert portfolio")
    }

    suspend fun findById(portfolioId: UUID): PortfolioEntity? = dbQuery {
        PortfoliosTable
            .selectAll()
            .where { PortfoliosTable.portfolioId eq portfolioId }
            .singleOrNull()?.toPortfolioEntity()
    }

    suspend fun findByUser(userId: Long, limit: Int, offset: Long = 0): List<PortfolioEntity> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        PortfoliosTable
            .selectAll()
            .where { PortfoliosTable.userId eq userId }
            .orderBy(PortfoliosTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toPortfolioEntity() }
    }

    suspend fun listAll(limit: Int, offset: Long = 0): List<PortfolioEntity> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        PortfoliosTable.selectAll()
            .orderBy(PortfoliosTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toPortfolioEntity() }
    }

    suspend fun searchByName(userId: Long, query: String, limit: Int, offset: Long = 0): List<PortfolioEntity> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        val pattern = "%${query.lowercase()}%"
        PortfoliosTable
            .selectAll()
            .where {
                (PortfoliosTable.userId eq userId) and (LowerCase(PortfoliosTable.name) like pattern)
            }
            .orderBy(PortfoliosTable.name, SortOrder.ASC)
            .limit(limit, offset)
            .map { it.toPortfolioEntity() }
    }

    suspend fun update(portfolioId: UUID, update: PortfolioUpdate): PortfolioEntity? = dbQuery {
        val values = update.toColumnValues()
        if (values.isEmpty()) {
            return@dbQuery findById(portfolioId)
        }
        val updated = PortfoliosTable.update({ PortfoliosTable.portfolioId eq portfolioId }) {
            it.setValues(values)
        }
        if (updated > 0) {
            PortfoliosTable
                .selectAll()
                .where { PortfoliosTable.portfolioId eq portfolioId }
                .singleOrNull()?.toPortfolioEntity()
        } else {
            null
        }
    }

    suspend fun delete(portfolioId: UUID): Boolean = dbQuery {
        PortfoliosTable.deleteWhere { PortfoliosTable.portfolioId eq portfolioId } > 0
    }
}
