package netflow2

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Netflow2PullWindowTest {
    @Test
    fun `splits ranges into three-year windows`() {
        val from = LocalDate.of(2020, 1, 1)
        val till = LocalDate.of(2026, 6, 1)

        val windows = Netflow2PullWindow.split(from, till)

        assertEquals(
            listOf(
                Netflow2PullWindow(fromInclusive = LocalDate.of(2020, 1, 1), tillExclusive = LocalDate.of(2023, 1, 1)),
                Netflow2PullWindow(fromInclusive = LocalDate.of(2023, 1, 1), tillExclusive = LocalDate.of(2026, 1, 1)),
                Netflow2PullWindow(fromInclusive = LocalDate.of(2026, 1, 1), tillExclusive = LocalDate.of(2026, 6, 1)),
            ),
            windows,
        )
    }

    @Test
    fun `keeps single window when exactly three years`() {
        val from = LocalDate.of(2020, 5, 10)
        val till = LocalDate.of(2023, 5, 10)

        val windows = Netflow2PullWindow.split(from, till)

        assertEquals(
            listOf(
                Netflow2PullWindow(fromInclusive = from, tillExclusive = till),
            ),
            windows,
        )
    }

    @Test
    fun `creates tail window when range longer than three years`() {
        val from = LocalDate.of(2019, 2, 15)
        val till = LocalDate.of(2022, 3, 1)

        val windows = Netflow2PullWindow.split(from, till)

        assertEquals(
            listOf(
                Netflow2PullWindow(fromInclusive = from, tillExclusive = LocalDate.of(2022, 2, 15)),
                Netflow2PullWindow(fromInclusive = LocalDate.of(2022, 2, 15), tillExclusive = till),
            ),
            windows,
        )
    }

    @Test
    fun `splits across leap day without overlap`() {
        val from = LocalDate.of(2019, 2, 28)
        val till = LocalDate.of(2022, 3, 1)

        val windows = Netflow2PullWindow.split(from, till)

        assertEquals(
            listOf(
                Netflow2PullWindow(fromInclusive = from, tillExclusive = LocalDate.of(2022, 2, 28)),
                Netflow2PullWindow(fromInclusive = LocalDate.of(2022, 2, 28), tillExclusive = till),
            ),
            windows,
        )
    }

    @Test
    fun `returns empty list for empty range`() {
        val date = LocalDate.of(2024, 1, 1)

        val windows = Netflow2PullWindow.split(date, date)

        assertEquals(emptyList(), windows)
    }

    @Test
    fun `rejects inverted range`() {
        val from = LocalDate.of(2024, 1, 1)
        val till = LocalDate.of(2023, 12, 31)

        assertFailsWith<IllegalArgumentException> {
            Netflow2PullWindow.split(from, till)
        }
    }

    @Test
    fun `converts to Moex inclusive parameters`() {
        val window =
            Netflow2PullWindow(
                fromInclusive = LocalDate.of(2024, 1, 1),
                tillExclusive = LocalDate.of(2024, 2, 1),
            )

        val (fromParam, tillParam) = window.toMoexQueryParams()

        assertEquals(LocalDate.of(2024, 1, 1), fromParam)
        assertEquals(LocalDate.of(2024, 1, 31), tillParam)
    }

    @Test
    fun `splitInclusive keeps upper bound included`() {
        val from = LocalDate.of(2024, 1, 1)
        val tillInclusive = LocalDate.of(2024, 1, 3)

        val windows = Netflow2PullWindow.splitInclusive(from, tillInclusive)

        assertEquals(
            listOf(
                Netflow2PullWindow(fromInclusive = from, tillExclusive = LocalDate.of(2024, 1, 4)),
            ),
            windows,
        )
    }

    @Test
    fun `splitInclusive returns single window for same-day range`() {
        val date = LocalDate.of(2024, 6, 1)

        val windows = Netflow2PullWindow.splitInclusive(date, date)

        assertEquals(
            listOf(
                Netflow2PullWindow(fromInclusive = date, tillExclusive = date.plusDays(1)),
            ),
            windows,
        )
    }
}
