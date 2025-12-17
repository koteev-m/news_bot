package alerts

import db.DatabaseFactory
import java.sql.Date
import java.sql.SQLException
import java.time.LocalDate
import kotlinx.serialization.json.Json
import javax.sql.DataSource

class AlertsRepositoryPostgres(
    private val dataSource: DataSource? = null,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : AlertsRepository {

    override fun getState(userId: Long): FsmState {
        val sql = "SELECT state_json FROM alerts_fsm_state WHERE user_id = ?"
        val stateJson = withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }
        return stateJson?.let { json.decodeFromString(FsmState.serializer(), it) } ?: FsmState.IDLE
    }

    override fun setState(userId: Long, state: FsmState) {
        val stateJson = json.encodeToString(FsmState.serializer(), state)
        val sql = """
            INSERT INTO alerts_fsm_state(user_id, state_json, updated_at)
            VALUES (?, ?::jsonb, now())
            ON CONFLICT (user_id) DO UPDATE
            SET state_json = EXCLUDED.state_json, updated_at = now()
        """.trimIndent()
        withWriteConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setString(2, stateJson)
                stmt.executeUpdate()
            }
        }
    }

    override fun getDailyPushCount(userId: Long, date: LocalDate): Int {
        val sql = "SELECT push_count FROM alerts_daily_budget WHERE user_id = ? AND day = ?"
        val count = withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setDate(2, Date.valueOf(date))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getInt(1)
                }
            }
        }
        return count ?: 0
    }

    override fun incDailyPushCount(userId: Long, date: LocalDate) {
        val sql = """
            INSERT INTO alerts_daily_budget(user_id, day, push_count, updated_at)
            VALUES (?, ?, 1, now())
            ON CONFLICT (user_id) DO UPDATE SET
                day = EXCLUDED.day,
                push_count = CASE
                    WHEN alerts_daily_budget.day = EXCLUDED.day THEN alerts_daily_budget.push_count + 1
                    ELSE 1
                END,
                updated_at = now()
        """.trimIndent()
        withWriteConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setDate(2, Date.valueOf(date))
                stmt.executeUpdate()
            }
        }
    }

    private fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        val ds = dataSource
        return if (ds != null) {
            ds.connection.use(block)
        } else {
            DatabaseFactory.withConnection(block)
        }
    }

    private fun <T> withWriteConnection(block: (java.sql.Connection) -> T): T = withConnection { conn ->
        val autoCommit = conn.autoCommit
        try {
            val result = block(conn)
            if (!autoCommit) {
                conn.commit()
            }
            result
        } catch (e: Exception) {
            if (!autoCommit) {
                runCatching { conn.rollback() }
            }
            if (e is SQLException) throw e else throw SQLException(e)
        }
    }
}
