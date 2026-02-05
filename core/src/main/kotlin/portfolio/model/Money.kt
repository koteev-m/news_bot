package portfolio.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

private const val SCALE = 8

private fun BigDecimal.norm(): BigDecimal = this.setScale(SCALE, RoundingMode.HALF_UP)

@Serializable
data class Money(
    @Contextual val amount: BigDecimal,
    val ccy: String,
) : Comparable<Money> {
    val currency: String
        get() = ccy

    init {
        require(ccy.matches(Regex("^[A-Z]{3}$"))) { "Currency must be ISO-4217 3 letters, got '$ccy'" }
    }

    private fun requireSame(other: Money) {
        require(ccy == other.ccy) { "Currency mismatch: $ccy vs ${other.ccy}" }
    }

    operator fun plus(other: Money): Money {
        requireSame(other)
        return Money(amount.add(other.amount).norm(), ccy)
    }

    operator fun minus(other: Money): Money {
        requireSame(other)
        return Money(amount.subtract(other.amount).norm(), ccy)
    }

    operator fun times(multiplier: BigDecimal): Money = Money(amount.multiply(multiplier).norm(), ccy)

    operator fun times(multiplier: Long): Money = times(BigDecimal.valueOf(multiplier))

    operator fun times(multiplier: Int): Money = times(multiplier.toLong())

    operator fun unaryMinus(): Money = Money(amount.negate().norm(), ccy)

    override operator fun compareTo(other: Money): Int {
        requireSame(other)
        return amount.compareTo(other.amount)
    }

    companion object {
        fun zero(ccy: String): Money =
            Money(
                BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP),
                ccy.trim().uppercase(Locale.ROOT),
            )

        fun of(
            amount: BigDecimal,
            ccy: String,
        ): Money = Money(amount.norm(), ccy.trim().uppercase(Locale.ROOT))
    }
}
