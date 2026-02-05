package portfolio

import portfolio.model.Money
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun `creates money with normalized scale and uppercase currency`() {
        val money = Money.of(BigDecimal("10.00"), "usd")

        assertEquals(BigDecimal("10.00000000"), money.amount)
        assertEquals(8, money.amount.scale())
        assertEquals("USD", money.currency)
    }

    @Test
    fun `adds money in the same currency`() {
        val first = Money.of(BigDecimal("10.10"), "USD")
        val second = Money.of(BigDecimal("5.20"), "USD")

        val result = first + second

        assertEquals(BigDecimal("15.30000000"), result.amount)
        assertEquals("USD", result.currency)
    }

    @Test
    fun `subtraction keeps scale normalized`() {
        val first = Money.of(BigDecimal("100.000"), "USD")
        val second = Money.of(BigDecimal("40.50"), "USD")

        val result = first - second

        assertEquals(BigDecimal("59.50000000"), result.amount)
    }

    @Test
    fun `multiplication by big decimal keeps scale normalized`() {
        val money = Money.of(BigDecimal("10"), "USD")

        val result = money * BigDecimal("2.50")

        assertEquals(BigDecimal("25.00000000"), result.amount)
    }

    @Test
    fun `multiplication by integer delegates to big decimal multiplier`() {
        val money = Money.of(BigDecimal("7.5"), "USD")

        val result = money * 3

        assertEquals(BigDecimal("22.50000000"), result.amount)
    }

    @Test
    fun `fails when currencies do not match`() {
        val usd = Money.of(BigDecimal.ONE, "USD")
        val eur = Money.of(BigDecimal.ONE, "EUR")

        assertFailsWith<IllegalArgumentException> { usd + eur }
        assertFailsWith<IllegalArgumentException> { usd - eur }
    }

    @Test
    fun `fails when currency code is invalid`() {
        assertFailsWith<IllegalArgumentException> { Money.of(BigDecimal.ONE, "US") }
        assertFailsWith<IllegalArgumentException> { Money.of(BigDecimal.ONE, "usd1") }
    }

    @Test
    fun `unary minus returns negated amount`() {
        val money = Money.of(BigDecimal("12.34"), "USD")

        val result = -money

        assertEquals(BigDecimal("-12.34000000"), result.amount)
    }
}
