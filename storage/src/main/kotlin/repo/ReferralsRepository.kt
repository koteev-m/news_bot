package repo

import db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import referrals.ReferralCode
import referrals.ReferralsPort
import referrals.UTM

object ReferralsTable : Table("referrals") {
    val refCode = text("ref_code")
    val ownerUserId = long("owner_user_id")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(refCode)
}

object ReferralVisitsTable : Table("referral_visits") {
    val id = long("id").autoIncrement()
    val refCode = text("ref_code").references(ReferralsTable.refCode, onDelete = ReferenceOption.CASCADE)
    val tgUserId = long("tg_user_id").nullable()
    val utmSource = text("utm_source").nullable()
    val utmMedium = text("utm_medium").nullable()
    val utmCampaign = text("utm_campaign").nullable()
    val ctaId = text("cta_id").nullable()
    val firstSeen = timestampWithTimeZone("first_seen")
    override val primaryKey = PrimaryKey(id)
}

class ReferralsRepository : ReferralsPort {
    override suspend fun create(ownerUserId: Long, code: String): ReferralCode = dbQuery {
        ReferralsTable.insertIgnore { statement ->
            statement[refCode] = code
            statement[ReferralsTable.ownerUserId] = ownerUserId
        }
        ReferralsTable.select { ReferralsTable.refCode eq code }
            .limit(1)
            .first()
            .let { row -> ReferralCode(row[ReferralsTable.refCode], row[ReferralsTable.ownerUserId]) }
    }

    override suspend fun find(code: String): ReferralCode? = dbQuery {
        ReferralsTable.select { ReferralsTable.refCode eq code }
            .limit(1)
            .firstOrNull()
            ?.let { row -> ReferralCode(row[ReferralsTable.refCode], row[ReferralsTable.ownerUserId]) }
    }

    override suspend fun recordVisit(code: String, tgUserId: Long?, utm: UTM) {
        dbQuery {
            ReferralVisitsTable.insertIgnore {
                it[refCode] = code
                it[ReferralVisitsTable.tgUserId] = tgUserId
                it[utmSource] = utm.source
                it[utmMedium] = utm.medium
                it[utmCampaign] = utm.campaign
                it[ctaId] = utm.ctaId
            }
        }
    }

    override suspend fun attachUser(code: String, tgUserId: Long) {
        dbQuery {
            val existing = ReferralVisitsTable
                .select { (ReferralVisitsTable.refCode eq code) and (ReferralVisitsTable.tgUserId eq tgUserId) }
                .limit(1)
                .firstOrNull()
            if (existing != null) {
                return@dbQuery
            }
            val pending = ReferralVisitsTable
                .select { (ReferralVisitsTable.refCode eq code) and ReferralVisitsTable.tgUserId.isNull() }
                .orderBy(ReferralVisitsTable.id, SortOrder.ASC)
                .limit(1)
                .firstOrNull()
            if (pending != null) {
                ReferralVisitsTable.update({ ReferralVisitsTable.id eq pending[ReferralVisitsTable.id] }) {
                    it[ReferralVisitsTable.tgUserId] = tgUserId
                }
            } else {
                ReferralVisitsTable.insertIgnore {
                    it[refCode] = code
                    it[ReferralVisitsTable.tgUserId] = tgUserId
                }
            }
        }
    }
}
