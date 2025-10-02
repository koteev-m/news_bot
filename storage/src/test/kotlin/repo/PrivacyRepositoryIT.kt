package repo

import db.tables.AlertsEventsTable
import db.tables.AlertsRulesTable
import db.tables.BotStartsTable
import db.tables.PortfoliosTable
import db.tables.UsersTable
import it.TestDb
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Test
import privacy.ErasureCfg
import privacy.PrivacyConfig
import privacy.PrivacyServiceImpl
import repo.PrivacyErasureQueue
import repo.PrivacyRepository
import repo.UserAlertOverridesTable
import repo.UserSubscriptionsTable
import repo.EventsTable
import privacy.RetentionCfg
import repo.tables.InstrumentsTable
import repo.tables.PositionsTable
import repo.tables.TradesTable
import repo.tables.ValuationsDailyTable

class PrivacyRepositoryIT {
    @Test
    fun `runErasure removes and anonymizes user data`() = runBlocking {
        TestDb.withMigratedDatabase { _ ->
            val targetUser = TestDb.createUser(telegramUserId = 111L)
            val otherUser = TestDb.createUser(telegramUserId = 222L)

            val instrumentId = TestDb.tx {
                InstrumentsTable.insert {
                    it[InstrumentsTable.clazz] = "EQUITY"
                    it[InstrumentsTable.exchange] = "MOEX"
                    it[InstrumentsTable.board] = "TQBR"
                    it[InstrumentsTable.symbol] = "SBER"
                    it[InstrumentsTable.isin] = "RU0009029540"
                    it[InstrumentsTable.cgId] = "sber"
                    it[InstrumentsTable.currency] = "RUB"
                    it[InstrumentsTable.createdAt] = Instant.parse("2024-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
                } get InstrumentsTable.instrumentId
            }

            val portfolioId = TestDb.tx {
                PortfoliosTable.insert {
                    it[PortfoliosTable.userId] = targetUser
                    it[PortfoliosTable.name] = "My Portfolio"
                    it[PortfoliosTable.baseCurrency] = "RUB"
                    it[PortfoliosTable.isActive] = true
                    it[PortfoliosTable.createdAt] = Instant.parse("2024-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
                } get PortfoliosTable.portfolioId
            }

            TestDb.tx {
                EventsTable.insert {
                    it[EventsTable.ts] = Instant.parse("2024-01-10T00:00:00Z").atOffset(ZoneOffset.UTC)
                    it[EventsTable.userId] = targetUser
                    it[EventsTable.type] = "test_event"
                    it[EventsTable.eventSource] = "it"
                    it[EventsTable.sessionId] = "sess-1"
                    it[EventsTable.props] = Json.parseToJsonElement("""{"foo":1}""").jsonObject
                }
                UserAlertOverridesTable.insert {
                    it[UserAlertOverridesTable.userId] = targetUser
                    it[UserAlertOverridesTable.payload] = Json.parseToJsonElement("""{"quiet":true}""")
                    it[UserAlertOverridesTable.updatedAt] = Instant.parse("2024-01-11T00:00:00Z").atOffset(ZoneOffset.UTC)
                }
                val ruleId = AlertsRulesTable.insert {
                    it[AlertsRulesTable.userId] = targetUser
                    it[AlertsRulesTable.portfolioId] = portfolioId
                    it[AlertsRulesTable.instrumentId] = instrumentId
                    it[AlertsRulesTable.kind] = "PRICE_CHANGE"
                    it[AlertsRulesTable.windowMinutes] = 15
                    it[AlertsRulesTable.threshold] = BigDecimal("1.0")
                    it[AlertsRulesTable.enabled] = true
                    it[AlertsRulesTable.cooldownMinutes] = 30
                    it[AlertsRulesTable.hysteresis] = BigDecimal("0.0")
                    it[AlertsRulesTable.createdAt] = Instant.parse("2024-01-12T00:00:00Z").atOffset(ZoneOffset.UTC)
                } get AlertsRulesTable.ruleId
                AlertsEventsTable.insert {
                    it[AlertsEventsTable.ruleId] = ruleId
                    it[AlertsEventsTable.ts] = Instant.parse("2024-01-12T01:00:00Z").atOffset(ZoneOffset.UTC)
                    it[AlertsEventsTable.payload] = JsonNull
                    it[AlertsEventsTable.delivered] = true
                }
                UserSubscriptionsTable.insert {
                    it[UserSubscriptionsTable.userId] = targetUser
                    it[UserSubscriptionsTable.tier] = "BASIC"
                    it[UserSubscriptionsTable.status] = "ACTIVE"
                    it[UserSubscriptionsTable.startedAt] = Instant.parse("2024-01-05T00:00:00Z").atOffset(ZoneOffset.UTC)
                    it[UserSubscriptionsTable.expiresAt] = Instant.parse("2024-02-05T00:00:00Z").atOffset(ZoneOffset.UTC)
                    it[UserSubscriptionsTable.provider] = "STARS"
                }
                TradesTable.insert {
                    it[TradesTable.portfolioId] = portfolioId
                    it[TradesTable.instrumentId] = instrumentId
                    it[TradesTable.datetime] = Instant.parse("2024-01-06T00:00:00Z").atOffset(ZoneOffset.UTC)
                    it[TradesTable.side] = "BUY"
                    it[TradesTable.quantity] = BigDecimal.ONE
                    it[TradesTable.price] = BigDecimal("100")
                    it[TradesTable.priceCurrency] = "RUB"
                    it[TradesTable.fee] = BigDecimal.ZERO
                    it[TradesTable.feeCurrency] = "RUB"
                    it[TradesTable.createdAt] = Instant.parse("2024-01-06T00:05:00Z").atOffset(ZoneOffset.UTC)
                }
                PositionsTable.insert {
                    it[PositionsTable.portfolioId] = portfolioId
                    it[PositionsTable.instrumentId] = instrumentId
                    it[PositionsTable.qty] = BigDecimal.ONE
                    it[PositionsTable.avgPrice] = BigDecimal("100")
                    it[PositionsTable.avgPriceCcy] = "RUB"
                    it[PositionsTable.updatedAt] = Instant.parse("2024-01-06T00:10:00Z").atOffset(ZoneOffset.UTC)
                }
                ValuationsDailyTable.insert {
                    it[ValuationsDailyTable.portfolioId] = portfolioId
                    it[ValuationsDailyTable.date] = LocalDate.parse("2024-01-06")
                    it[ValuationsDailyTable.valueRub] = BigDecimal("100")
                    it[ValuationsDailyTable.pnlDay] = BigDecimal.ZERO
                    it[ValuationsDailyTable.pnlTotal] = BigDecimal.ZERO
                    it[ValuationsDailyTable.drawdown] = BigDecimal.ZERO
                }
                BotStartsTable.insert {
                    it[BotStartsTable.userId] = targetUser
                    it[BotStartsTable.payload] = "start"
                    it[BotStartsTable.abVariant] = "A"
                    it[BotStartsTable.startedAt] = Instant.parse("2024-01-07T00:00:00Z").atOffset(ZoneOffset.UTC)
                }
                EventsTable.insert {
                    it[EventsTable.ts] = Instant.parse("2024-01-10T00:00:00Z").atOffset(ZoneOffset.UTC)
                    it[EventsTable.userId] = otherUser
                    it[EventsTable.type] = "other_event"
                    it[EventsTable.eventSource] = "it"
                    it[EventsTable.sessionId] = "sess-2"
                    it[EventsTable.props] = Json.parseToJsonElement("""{"foo":2}""").jsonObject
                }
            }

            val repo = PrivacyRepository()
            val service = PrivacyServiceImpl(
                repo,
                PrivacyConfig(
                    retention = RetentionCfg(analyticsDays = 90, alertsDays = 30, botStartsDays = 30),
                    erasure = ErasureCfg(enabled = true, dryRun = false, batchSize = 5000)
                )
            )

            repo.upsertRequest(targetUser)
            val report = service.runErasure(targetUser, dryRun = false)

            val queueStatus = TestDb.tx {
                PrivacyErasureQueue.selectAll().single()[PrivacyErasureQueue.status]
            }
            kotlin.test.assertEquals("DONE", queueStatus)
            kotlin.test.assertFalse(report.dryRun)
            kotlin.test.assertTrue(report.deleted["events"] ?: 0L > 0L)
            kotlin.test.assertTrue(report.anonymized["bot_starts"] ?: 0L > 0L)

            TestDb.tx {
                kotlin.test.assertEquals(0, EventsTable.selectAll().where { EventsTable.userId eq targetUser }.count())
                kotlin.test.assertEquals(1, EventsTable.selectAll().where { EventsTable.userId eq otherUser }.count())
                kotlin.test.assertEquals(0, UserAlertOverridesTable.selectAll().where { UserAlertOverridesTable.userId eq targetUser }.count())
                kotlin.test.assertEquals(0, AlertsRulesTable.selectAll().where { AlertsRulesTable.userId eq targetUser }.count())
                kotlin.test.assertEquals(0, AlertsEventsTable.selectAll().count())
                kotlin.test.assertEquals(0, UserSubscriptionsTable.selectAll().where { UserSubscriptionsTable.userId eq targetUser }.count())
                kotlin.test.assertEquals(0, PortfoliosTable.selectAll().where { PortfoliosTable.userId eq targetUser }.count())
                kotlin.test.assertEquals(0, TradesTable.selectAll().where { TradesTable.portfolioId eq portfolioId }.count())
                kotlin.test.assertEquals(0, PositionsTable.selectAll().where { PositionsTable.portfolioId eq portfolioId }.count())
                kotlin.test.assertEquals(0, ValuationsDailyTable.selectAll().where { ValuationsDailyTable.portfolioId eq portfolioId }.count())
                val botRecord = BotStartsTable.selectAll().single()
                kotlin.test.assertNull(botRecord[BotStartsTable.userId])
                kotlin.test.assertEquals("start", botRecord[BotStartsTable.payload])
                kotlin.test.assertEquals(0, UsersTable.selectAll().where { UsersTable.userId eq targetUser }.count())
                kotlin.test.assertEquals(1, UsersTable.selectAll().where { UsersTable.userId eq otherUser }.count())
            }
        }
    }
}
