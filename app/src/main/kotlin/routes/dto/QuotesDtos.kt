package routes.dto

import java.math.RoundingMode
import kotlinx.serialization.Serializable
import portfolio.model.Money

private const val MONEY_SCALE = 8

@Serializable
data class MoneyDto(
    val amount: String,
    val ccy: String,
)

fun Money.toDto(): MoneyDto = MoneyDto(
    amount = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString(),
    ccy = ccy,
)
