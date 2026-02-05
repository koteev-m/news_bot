package routes.dto

import kotlinx.serialization.Serializable
import portfolio.model.Money
import java.math.RoundingMode

private const val MONEY_SCALE = 8

@Serializable
data class MoneyDto(
    val amount: String, // scale=8, toPlainString
    val ccy: String,
)

fun Money.toDto(): MoneyDto =
    MoneyDto(
        amount = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString(),
        ccy = ccy,
    )
