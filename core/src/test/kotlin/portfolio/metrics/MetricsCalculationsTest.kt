package portfolio.metrics

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetricsCalculationsTest {
    @Test
    fun `irr with single year cashflow equals ten percent`() {
        val cashflows = listOf(
            CashflowEntry(LocalDate.of(2023, 1, 1), BigDecimal("-1000")),
            CashflowEntry(LocalDate.of(2024, 1, 1), BigDecimal("1100")),
        )

        val result = computeIrr(cashflows, LocalDate.of(2024, 1, 1), BigDecimal.ZERO)

        assertEquals(IrrStatus.OK, result.status)
        assertNotNull(result.irr)
        assertTrue(abs(result.irr - 0.1) < 1e-4)
    }

    @Test
    fun `irr returns no root for same sign cashflows`() {
        val cashflows = listOf(
            CashflowEntry(LocalDate.of(2024, 1, 1), BigDecimal("-1000")),
            CashflowEntry(LocalDate.of(2024, 6, 1), BigDecimal("-200")),
        )

        val result = computeIrr(cashflows, LocalDate.of(2024, 6, 1), BigDecimal.ZERO)

        assertEquals(IrrStatus.NO_ROOT, result.status)
        assertEquals(null, result.irr)
    }

    @Test
    fun `irr returns invalid input for single cashflow`() {
        val cashflows = listOf(
            CashflowEntry(LocalDate.of(2024, 1, 1), BigDecimal("-1000")),
        )

        val result = computeIrr(cashflows, LocalDate.of(2024, 1, 1), BigDecimal.ZERO)

        assertEquals(IrrStatus.INVALID_INPUT, result.status)
        assertEquals(null, result.irr)
    }

    @Test
    fun `irr guard handles strange data`() {
        val cashflows = listOf(
            CashflowEntry(LocalDate.of(2024, 1, 1), BigDecimal("-1000")),
            CashflowEntry(LocalDate.of(2024, 1, 2), BigDecimal("1")),
        )

        val result = computeIrr(cashflows, LocalDate.of(2024, 1, 2), BigDecimal.ZERO)

        assertTrue(result.status != IrrStatus.OK)
        assertEquals(null, result.irr)
    }

    @Test
    fun `twr without cashflows equals valuation growth`() {
        val valuations = listOf(
            ValuationEntry(LocalDate.of(2024, 1, 1), BigDecimal("100")),
            ValuationEntry(LocalDate.of(2024, 1, 2), BigDecimal("110")),
        )

        val result = computeTwr(valuations, emptyMap())

        assertEquals(TwrStatus.OK, result.status)
        assertNotNull(result.twr)
        assertTrue(abs(result.twr - 0.1) < 1e-8)
    }

    @Test
    fun `twr accounts for cashflow during day`() {
        val valuations = listOf(
            ValuationEntry(LocalDate.of(2024, 1, 1), BigDecimal("100")),
            ValuationEntry(LocalDate.of(2024, 1, 2), BigDecimal("160")),
        )
        val cashflows = mapOf(LocalDate.of(2024, 1, 2) to BigDecimal("50"))

        val result = computeTwr(valuations, cashflows)

        assertEquals(TwrStatus.OK, result.status)
        assertNotNull(result.twr)
        assertTrue(abs(result.twr - 0.1) < 1e-8)
    }

    @Test
    fun `twr returns insufficient data for single valuation`() {
        val valuations = listOf(
            ValuationEntry(LocalDate.of(2024, 1, 1), BigDecimal("100")),
        )

        val result = computeTwr(valuations, emptyMap())

        assertEquals(TwrStatus.INSUFFICIENT_DATA, result.status)
        assertEquals(null, result.twr)
    }
}
