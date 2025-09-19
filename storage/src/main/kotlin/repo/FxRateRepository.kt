package repo

import db.DatabaseFactory.dbQuery
import java.time.Instant
import model.FxRate
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import repo.mapper.setValues
import repo.mapper.toColumnValues
import repo.mapper.toDbTimestamp
import repo.mapper.toFxRate
import repo.tables.FxRatesTable

class FxRateRepository {
    suspend fun upsert(rate: FxRate): FxRate = dbQuery {
        val predicate = (FxRatesTable.ccy eq rate.ccy) and (FxRatesTable.ts eq rate.ts.toDbTimestamp())
        val updated = FxRatesTable.update({ predicate }) {
            it.setValues(rate.toColumnValues())
        }
        if (updated == 0) {
            FxRatesTable.insert {
                it.setValues(rate.toColumnValues())
            }
        }
        FxRatesTable.select { predicate }.single().toFxRate()
    }

    suspend fun findLatest(ccy: String): FxRate? = dbQuery {
        FxRatesTable.select { FxRatesTable.ccy eq ccy }
            .orderBy(FxRatesTable.ts, SortOrder.DESC)
            .limit(1)
            .singleOrNull()?.toFxRate()
    }

    suspend fun find(ccy: String, timestamp: Instant): FxRate? = dbQuery {
        FxRatesTable.select {
            (FxRatesTable.ccy eq ccy) and (FxRatesTable.ts eq timestamp.toDbTimestamp())
        }.singleOrNull()?.toFxRate()
    }

    suspend fun list(ccy: String, limit: Int, offset: Long = 0): List<FxRate> = dbQuery {
        require(limit > 0) { "limit must be positive" }
        FxRatesTable.select { FxRatesTable.ccy eq ccy }
            .orderBy(FxRatesTable.ts, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toFxRate() }
    }

    suspend fun delete(ccy: String, timestamp: Instant): Boolean = dbQuery {
        FxRatesTable.deleteWhere {
            (FxRatesTable.ccy eq ccy) and (FxRatesTable.ts eq timestamp.toDbTimestamp())
        } > 0
    }
}
