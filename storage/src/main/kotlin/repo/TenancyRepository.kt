package repo

import db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import tenancy.QuotaRepo
import tenancy.Quotas
import tenancy.Role
import tenancy.Tenant

object OrgsTable : Table("orgs") {
    val orgId = long("org_id").autoIncrement()
    val name = text("name")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(orgId)
}

object TenantsTable : Table("tenants") {
    val tenantId = long("tenant_id").autoIncrement()
    val orgId = long("org_id")
    val slug = text("slug")
    val displayName = text("display_name")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(tenantId)
}

object MembersTable : Table("members") {
    val tenantId = long("tenant_id")
    val userId = long("user_id")
    val role = text("role")
    val addedAt = timestampWithTimeZone("added_at")
    override val primaryKey = PrimaryKey(tenantId, userId)
}

object QuotasTable : Table("quotas") {
    val tenantId = long("tenant_id")
    val maxPortfolios = integer("max_portfolios")
    val maxAlerts = integer("max_alerts")
    val rpsSoft = integer("rps_soft")
    val rpsHard = integer("rps_hard")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId)
}

object TenantPortfoliosTable : Table("portfolios") {
    val tenantId = long("tenant_id")
}

object TenantAlertsRulesTable : Table("alerts_rules") {
    val tenantId = long("tenant_id")
}

open class TenancyRepository : QuotaRepo {
    open suspend fun findTenantBySlug(slug: String): Tenant? = dbQuery {
        TenantsTable.select { TenantsTable.slug eq slug }.firstOrNull()?.let {
            Tenant(
                tenantId = it[TenantsTable.tenantId],
                orgId = it[TenantsTable.orgId],
                slug = it[TenantsTable.slug],
                displayName = it[TenantsTable.displayName]
            )
        }
    }

    open suspend fun rolesForUser(tenantId: Long, userId: Long): Set<Role> = dbQuery {
        MembersTable.select { (MembersTable.tenantId eq tenantId) and (MembersTable.userId eq userId) }
            .map { Role.valueOf(it[MembersTable.role]) }
            .toSet()
    }

    override suspend fun getQuotas(tenantId: Long): Quotas = dbQuery {
        QuotasTable.select { QuotasTable.tenantId eq tenantId }.first().let {
            Quotas(
                tenantId = it[QuotasTable.tenantId],
                maxPortfolios = it[QuotasTable.maxPortfolios],
                maxAlerts = it[QuotasTable.maxAlerts],
                rpsSoft = it[QuotasTable.rpsSoft],
                rpsHard = it[QuotasTable.rpsHard]
            )
        }
    }

    override suspend fun countPortfolios(tenantId: Long): Int = dbQuery {
        TenantPortfoliosTable.select { TenantPortfoliosTable.tenantId eq tenantId }.count().toInt()
    }

    override suspend fun countAlerts(tenantId: Long): Int = dbQuery {
        TenantAlertsRulesTable.select { TenantAlertsRulesTable.tenantId eq tenantId }.count().toInt()
    }
}
