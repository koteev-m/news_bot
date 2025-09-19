package portfolio.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class DateRange(
    @Contextual val from: LocalDate,
    @Contextual val to: LocalDate
) {
    init {
        require(!from.isAfter(to)) { "Start date must not be after end date" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)

    val lengthInDays: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    fun overlaps(other: DateRange): Boolean = from <= other.to && other.from <= to
}
