package db.tables

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import java.math.BigDecimal
import java.util.UUID

private fun genRandomUuid() = CustomFunction<UUID>("gen_random_uuid", org.jetbrains.exposed.sql.UUIDColumnType())

object AlertsRulesTable : Table("alerts_rules") {
    val ruleId = uuid("rule_id").defaultExpression(genRandomUuid())
    val userId = long("user_id").references(UsersTable.userId, onDelete = ReferenceOption.CASCADE).nullable()
    val portfolioId = uuid(
        "portfolio_id"
    ).references(PortfoliosTable.portfolioId, onDelete = ReferenceOption.CASCADE).nullable()
    val instrumentId = long(
        "instrument_id"
    ).references(InstrumentsTable.instrumentId, onDelete = ReferenceOption.CASCADE).nullable()
    val topic = text("topic").nullable()
    val kind = text("kind")
    val windowMinutes = integer("window_minutes")
    val threshold = decimal("threshold", 20, 8)
    val enabled = bool("enabled").default(true)
    val cooldownMinutes = integer("cooldown_minutes").default(60)
    val hysteresis = decimal("hysteresis", 10, 4).default(BigDecimal("0.0"))
    val quietHoursJson = jsonb("quiet_hours_json", Json, JsonElement.serializer()).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(ruleId)
    init {
        index("idx_alerts_user_instr", false, userId, instrumentId)
    }
}

object AlertsEventsTable : Table("alerts_events") {
    val eventId = long("event_id").autoIncrement()
    val ruleId = uuid("rule_id").references(AlertsRulesTable.ruleId, onDelete = ReferenceOption.CASCADE)
    val ts = timestampWithTimeZone("ts")
    val payload = jsonb("payload", Json, JsonElement.serializer()).nullable()
    val delivered = bool("delivered").default(false)
    val mutedReason = text("muted_reason").nullable()
    override val primaryKey = PrimaryKey(eventId)
    init {
        index("idx_alerts_events_rule_ts", false, ruleId, ts)
    }
}
