package repo

import db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import repo.mapper.setValues
import repo.mapper.toInstrumentAliasEntity
import repo.mapper.toInstrumentEntity
import repo.mapper.toColumnValues
import repo.model.InstrumentAliasEntity
import repo.model.InstrumentEntity
import repo.model.InstrumentUpdate
import repo.model.NewInstrument
import repo.model.NewInstrumentAlias
import repo.tables.InstrumentAliasesTable
import repo.tables.InstrumentsTable

class InstrumentRepository {
    suspend fun createInstrument(newInstrument: NewInstrument): InstrumentEntity = dbQuery {
        val statement = InstrumentsTable.insert {
            it.setValues(newInstrument.toColumnValues())
        }
        statement.resultedValues?.singleOrNull()?.toInstrumentEntity()
            ?: error("Failed to insert instrument")
    }

    suspend fun updateInstrument(instrumentId: Long, update: InstrumentUpdate): InstrumentEntity? = dbQuery {
        val values = update.toColumnValues()
        if (values.isEmpty()) {
            return@dbQuery findById(instrumentId)
        }
        val updated = InstrumentsTable.update({ InstrumentsTable.instrumentId eq instrumentId }) {
            it.setValues(values)
        }
        if (updated > 0) {
            InstrumentsTable
                .selectAll()
                .where { InstrumentsTable.instrumentId eq instrumentId }
                .singleOrNull()?.toInstrumentEntity()
        } else {
            null
        }
    }

    suspend fun deleteInstrument(instrumentId: Long): Boolean = dbQuery {
        InstrumentsTable.deleteWhere { InstrumentsTable.instrumentId eq instrumentId } > 0
    }

    suspend fun findById(instrumentId: Long): InstrumentEntity? = dbQuery {
        InstrumentsTable
            .selectAll()
            .where { InstrumentsTable.instrumentId eq instrumentId }
            .singleOrNull()?.toInstrumentEntity()
    }

    suspend fun findByIsin(isin: String): InstrumentEntity? = dbQuery {
        InstrumentsTable
            .selectAll()
            .where { InstrumentsTable.isin eq isin }
            .singleOrNull()?.toInstrumentEntity()
    }

    suspend fun findBySymbol(exchange: String, board: String?, symbol: String): InstrumentEntity? = dbQuery {
        InstrumentsTable
            .selectAll()
            .where {
                val boardCondition = board?.let { InstrumentsTable.board eq it } ?: InstrumentsTable.board.isNull()
                (InstrumentsTable.exchange eq exchange) and boardCondition and (InstrumentsTable.symbol eq symbol)
            }
            .singleOrNull()?.toInstrumentEntity()
    }

    suspend fun search(query: String, limit: Int, offset: Long = 0): List<InstrumentEntity> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        val pattern = "%${query}%"
        InstrumentsTable
            .selectAll()
            .where {
                (InstrumentsTable.symbol like pattern) or (InstrumentsTable.exchange like pattern)
            }
            .orderBy(InstrumentsTable.symbol, SortOrder.ASC)
            .limit(limit, offset)
            .map { it.toInstrumentEntity() }
    }

    suspend fun listAliases(instrumentId: Long): List<InstrumentAliasEntity> = dbQuery {
        InstrumentAliasesTable
            .selectAll()
            .where { InstrumentAliasesTable.instrumentId eq instrumentId }
            .orderBy(InstrumentAliasesTable.alias, SortOrder.ASC)
            .map { it.toInstrumentAliasEntity() }
    }

    suspend fun addAlias(newAlias: NewInstrumentAlias): InstrumentAliasEntity = dbQuery {
        val statement = InstrumentAliasesTable.insert {
            it.setValues(newAlias.toColumnValues())
        }
        statement.resultedValues?.singleOrNull()?.toInstrumentAliasEntity()
            ?: error("Failed to insert instrument alias")
    }

    suspend fun removeAlias(aliasId: Long): Boolean = dbQuery {
        InstrumentAliasesTable.deleteWhere { InstrumentAliasesTable.aliasId eq aliasId } > 0
    }

    suspend fun findAlias(alias: String, source: String): InstrumentAliasEntity? = dbQuery {
        InstrumentAliasesTable
            .selectAll()
            .where {
                (InstrumentAliasesTable.alias eq alias) and (InstrumentAliasesTable.sourceCol eq source)
            }
            .singleOrNull()?.toInstrumentAliasEntity()
    }

    suspend fun listAll(limit: Int, offset: Long = 0): List<InstrumentEntity> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        InstrumentsTable.selectAll()
            .orderBy(InstrumentsTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toInstrumentEntity() }
    }
}
