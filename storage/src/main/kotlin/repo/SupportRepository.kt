package repo

import db.DatabaseFactory.dbQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

object SupportFaqTable : Table("support_faq") {
    val id = long("id").autoIncrement()
    val locale = text("locale")
    val slug = text("slug")
    val title = text("title")
    val bodyMd = text("body_md")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object SupportTicketsTable : Table("support_tickets") {
    val ticketId = long("ticket_id").autoIncrement()
    val ts = timestampWithTimeZone("ts")
    val userId = long("user_id").nullable()
    val category = text("category")
    val locale = text("locale")
    val subject = text("subject")
    val message = text("message")
    val status = text("status")
    val appVersion = text("app_version").nullable()
    val deviceInfo = text("device_info").nullable()
    override val primaryKey = PrimaryKey(ticketId)
}

data class FaqItem(
    val locale: String,
    val slug: String,
    val title: String,
    val bodyMd: String,
    val updatedAt: Instant
)

data class SupportTicket(
    val ticketId: Long? = null,
    val ts: Instant? = null,
    val userId: Long?,
    val category: String,
    val locale: String,
    val subject: String,
    val message: String,
    val status: String = "OPEN",
    val appVersion: String?,
    val deviceInfo: String?
)

open class SupportRepository(private val clock: Clock = Clock.systemUTC()) {
    open suspend fun listFaq(localeValue: String): List<FaqItem> = dbQuery {
        SupportFaqTable
            .select { SupportFaqTable.locale eq localeValue }
            .orderBy(SupportFaqTable.slug, SortOrder.ASC)
            .map {
                FaqItem(
                    locale = it[SupportFaqTable.locale],
                    slug = it[SupportFaqTable.slug],
                    title = it[SupportFaqTable.title],
                    bodyMd = it[SupportFaqTable.bodyMd],
                    updatedAt = it[SupportFaqTable.updatedAt].toInstant()
                )
            }
    }

    open suspend fun upsertFaq(item: FaqItem) = dbQuery {
        val predicate = (SupportFaqTable.locale eq item.locale) and (SupportFaqTable.slug eq item.slug)
        val exists = SupportFaqTable.select { predicate }.any()
        if (!exists) {
            SupportFaqTable.insert {
                it[locale] = item.locale
                it[slug] = item.slug
                it[title] = item.title
                it[bodyMd] = item.bodyMd
                it[updatedAt] = item.updatedAt.atOffset(ZoneOffset.UTC)
            }
        } else {
            SupportFaqTable.update({ predicate }) {
                it[title] = item.title
                it[bodyMd] = item.bodyMd
                it[updatedAt] = Instant.now(clock).atOffset(ZoneOffset.UTC)
            }
        }
    }

    open suspend fun createTicket(ticket: SupportTicket): Long = dbQuery {
        SupportTicketsTable.insert {
            it[userId] = ticket.userId
            it[category] = ticket.category
            it[locale] = ticket.locale
            it[subject] = ticket.subject
            it[message] = ticket.message
            it[status] = ticket.status
            it[appVersion] = ticket.appVersion
            it[deviceInfo] = ticket.deviceInfo
            it[ts] = Instant.now(clock).atOffset(ZoneOffset.UTC)
        } get SupportTicketsTable.ticketId
    }

    open suspend fun listTickets(status: String?, limit: Int): List<SupportTicket> = dbQuery {
        val query = if (status.isNullOrBlank()) {
            SupportTicketsTable.selectAll()
        } else {
            SupportTicketsTable.select { SupportTicketsTable.status eq status }
        }
        query
            .orderBy(SupportTicketsTable.ts, SortOrder.DESC)
            .limit(limit)
            .map {
                SupportTicket(
                    ticketId = it[SupportTicketsTable.ticketId],
                    ts = it[SupportTicketsTable.ts].toInstant(),
                    userId = it[SupportTicketsTable.userId],
                    category = it[SupportTicketsTable.category],
                    locale = it[SupportTicketsTable.locale],
                    subject = it[SupportTicketsTable.subject],
                    message = it[SupportTicketsTable.message],
                    status = it[SupportTicketsTable.status],
                    appVersion = it[SupportTicketsTable.appVersion],
                    deviceInfo = it[SupportTicketsTable.deviceInfo]
                )
            }
    }

    open suspend fun updateStatus(id: Long, statusValue: String) = dbQuery {
        SupportTicketsTable.update({ SupportTicketsTable.ticketId eq id }) {
            it[status] = statusValue
        }
    }
}
