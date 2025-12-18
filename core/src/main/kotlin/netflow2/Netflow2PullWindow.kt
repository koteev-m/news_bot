package netflow2

import java.time.LocalDate

/**
 * Inclusive-exclusive window describing a Netflow2 pull interval.
 */
data class Netflow2PullWindow(
    val fromInclusive: LocalDate,
    val tillExclusive: LocalDate,
) {
    init {
        require(!fromInclusive.isAfter(tillExclusive)) { "fromInclusive must not be after tillExclusive" }
    }

    /**
     * Returns API-ready parameters where [fromInclusive] is sent as `from` and the last included date is
     * `tillExclusive.minusDays(1)`, keeping the wire contract inclusive on both ends while our internal
     * representation stays inclusive-exclusive to avoid overlaps between adjacent windows.
     */
    fun toMoexQueryParams(): Pair<LocalDate, LocalDate> {
        require(fromInclusive.isBefore(tillExclusive)) { "empty window cannot be converted to MOEX query params" }
        return fromInclusive to tillExclusive.minusDays(1)
    }

    companion object {
        private const val MAX_YEARS = 3L

        /**
         * Splits the requested range into windows limited by [MAX_YEARS] years.
         *
         * [fromInclusive] is included, [tillExclusive] is excluded to avoid overlapping borders between windows.
         */
        fun split(fromInclusive: LocalDate, tillExclusive: LocalDate): List<Netflow2PullWindow> {
            require(!fromInclusive.isAfter(tillExclusive)) { "fromInclusive must not be after tillExclusive" }
            if (fromInclusive == tillExclusive) {
                return emptyList()
            }

            val result = mutableListOf<Netflow2PullWindow>()
            var cursor = fromInclusive
            while (cursor.isBefore(tillExclusive)) {
                val windowEnd = cursor.plusYears(MAX_YEARS).coerceAtMost(tillExclusive)
                result += Netflow2PullWindow(cursor, windowEnd)
                cursor = windowEnd
            }
            return result
        }

        /**
         * Inclusive range splitter that preserves the right boundary.
         */
        fun splitInclusive(fromInclusive: LocalDate, tillInclusive: LocalDate): List<Netflow2PullWindow> {
            require(!fromInclusive.isAfter(tillInclusive)) { "fromInclusive must not be after tillInclusive" }
            return split(fromInclusive, tillInclusive.plusDays(1))
        }

        /**
         * Constructs an inclusive window, converting the inclusive [tillInclusive] boundary into the internal
         * inclusive-exclusive representation.
         */
        fun ofInclusive(fromInclusive: LocalDate, tillInclusive: LocalDate): Netflow2PullWindow {
            require(!fromInclusive.isAfter(tillInclusive)) { "fromInclusive must not be after tillInclusive" }
            return Netflow2PullWindow(fromInclusive, tillInclusive.plusDays(1))
        }

        private fun LocalDate.coerceAtMost(limit: LocalDate): LocalDate = if (this.isAfter(limit)) limit else this
    }
}
