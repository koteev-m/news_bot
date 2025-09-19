package portfolio.model

import java.math.BigDecimal
import java.util.Locale
import kotlin.ConsistentCopyVisibility
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@ConsistentCopyVisibility
@Serializable
data class Money internal constructor(
    @Contextual val amount: BigDecimal,
    val currency: String
) {
    init {
        require(isNormalized(amount)) { "Amount scale must be normalized" }
        require(CURRENCY_REGEX.matches(currency)) { "Currency must be a valid ISO 4217 code" }
    }

    operator fun plus(other: Money): Money {
        ensureSameCurrency(other)
        return of(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        ensureSameCurrency(other)
        return of(amount - other.amount, currency)
    }

    operator fun times(multiplier: BigDecimal): Money = of(amount.multiply(multiplier), currency)

    operator fun times(multiplier: Long): Money = times(BigDecimal.valueOf(multiplier))

    operator fun times(multiplier: Int): Money = times(multiplier.toLong())

    operator fun unaryMinus(): Money = of(amount.negate(), currency)

    private fun ensureSameCurrency(other: Money) {
        require(currency == other.currency) { "Currency mismatch: $currency != ${other.currency}" }
    }

    companion object {
        private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")

        fun of(amount: BigDecimal, currency: String): Money {
            val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
            require(CURRENCY_REGEX.matches(normalizedCurrency)) { "Currency must be a valid ISO 4217 code" }
            val normalizedAmount = normalize(amount)
            return Money(normalizedAmount, normalizedCurrency)
        }

        private fun normalize(amount: BigDecimal): BigDecimal {
            val stripped = amount.stripTrailingZeros()
            return if (stripped.scale() < 0) stripped.setScale(0) else stripped
        }

        internal fun isNormalized(amount: BigDecimal): Boolean = amount == normalize(amount)
    }
}
