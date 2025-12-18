package netflow2

import java.time.LocalDate

/**
 * Daily Netflow2 snapshot for a single security.
 *
 * All numeric values are nullable to allow storing partially populated payloads without discarding the row.
 */
data class Netflow2Row(
    val date: LocalDate,
    val ticker: String,
    val p30: Long?,
    val p70: Long?,
    val p100: Long?,
    val pv30: Long?,
    val pv70: Long?,
    val pv100: Long?,
    val vol: Long?,
    val oi: Long?,
)
