package repo

import db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import residency.ResidencyPolicy
import repo.sql.textArray

object ResidencyPolicies : Table("data_residency_policies") {
    val tenantId = long("tenant_id")
    val region = text("region")
    val dataClasses = textArray("data_classes")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId)
}

class ResidencyRepository {
    suspend fun getPolicy(tenantId: Long): ResidencyPolicy? = dbQuery {
        ResidencyPolicies
            .select { ResidencyPolicies.tenantId eq tenantId }
            .firstOrNull()
            ?.let {
                ResidencyPolicy(
                    tenantId = it[ResidencyPolicies.tenantId],
                    region = it[ResidencyPolicies.region],
                    dataClasses = it[ResidencyPolicies.dataClasses]
                )
            }
    }
}
