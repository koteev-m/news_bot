package portfolio.service

import portfolio.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private const val SCALE = 8

private fun BigDecimal.normalized(): BigDecimal = setScale(SCALE, RoundingMode.HALF_UP)

private fun money(
    amount: BigDecimal,
    ccy: String,
): Money = Money.zero(ccy).copy(amount = amount.normalized())

interface PositionCalc {
    data class Lot(
        val quantity: BigDecimal,
        val costBasis: Money,
    ) {
        init {
            require(quantity >= BigDecimal.ZERO) { "Lot quantity cannot be negative" }
        }

        val averagePrice: Money?
            get() =
                if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                    null
                } else {
                    money(costBasis.amount.divide(quantity, MATH_CONTEXT), costBasis.ccy)
                }
    }

    data class Position(
        val quantity: BigDecimal,
        val costBasis: Money,
        val lots: List<Lot> = emptyList(),
    ) {
        init {
            require(quantity >= BigDecimal.ZERO) { "Position quantity cannot be negative" }
            require(lots.all { it.costBasis.ccy == costBasis.ccy }) {
                "Lot currency must match position currency"
            }
        }

        val currency: String = costBasis.ccy

        val averagePrice: Money?
            get() =
                if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                    null
                } else {
                    money(costBasis.amount.divide(quantity, MATH_CONTEXT), currency)
                }

        companion object {
            fun empty(currency: String): Position =
                Position(
                    BigDecimal.ZERO,
                    Money.zero(currency),
                    emptyList(),
                )
        }
    }

    data class Result(
        val position: Position,
        val realizedPnl: Money,
    )

    fun applyBuy(
        position: Position,
        quantity: BigDecimal,
        price: Money,
        fees: Money = Money.zero(price.ccy),
    ): Result

    fun applySell(
        position: Position,
        quantity: BigDecimal,
        price: Money,
        fees: Money = Money.zero(price.ccy),
    ): Result

    class AverageCostCalc : PositionCalc {
        override fun applyBuy(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money,
        ): Result {
            validateInputs(position, quantity, price, fees)
            require(quantity > BigDecimal.ZERO) { "Buy quantity must be positive" }

            val totalCost = price.times(quantity) + fees
            val newQuantity = position.quantity + quantity
            val newCostBasis = position.costBasis + totalCost
            val updatedPosition = Position(newQuantity, newCostBasis)
            return Result(updatedPosition, Money.zero(price.ccy))
        }

        override fun applySell(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money,
        ): Result {
            validateInputs(position, quantity, price, fees)
            require(quantity > BigDecimal.ZERO) { "Sell quantity must be positive" }
            require(position.quantity >= quantity) { "Cannot sell more than current quantity" }

            val proceeds = price.times(quantity)
            val costSoldAmount =
                position.costBasis.amount
                    .multiply(quantity)
                    .divide(position.quantity, MATH_CONTEXT)
            val costSold = money(costSoldAmount, price.ccy)
            val newQuantity = position.quantity - quantity
            val newCostBasis =
                if (newQuantity.compareTo(BigDecimal.ZERO) == 0) {
                    Money.zero(price.ccy)
                } else {
                    position.costBasis - costSold
                }
            val realized = proceeds - costSold - fees
            val updatedPosition = Position(newQuantity, newCostBasis)
            return Result(updatedPosition, realized)
        }
    }

    class FifoCalc : PositionCalc {
        override fun applyBuy(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money,
        ): Result {
            validateInputs(position, quantity, price, fees)
            require(quantity > BigDecimal.ZERO) { "Buy quantity must be positive" }

            val totalCost = price.times(quantity) + fees
            val newLot = Lot(quantity, totalCost)
            val newQuantity = position.quantity + quantity
            val newCostBasis = position.costBasis + totalCost
            val newLots = position.lots + newLot
            val updatedPosition = Position(newQuantity, newCostBasis, newLots)
            return Result(updatedPosition, Money.zero(price.ccy))
        }

        override fun applySell(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money,
        ): Result {
            validateInputs(position, quantity, price, fees)
            require(quantity > BigDecimal.ZERO) { "Sell quantity must be positive" }
            require(position.quantity >= quantity) { "Cannot sell more than current quantity" }

            var remaining = quantity
            var costSoldAmount = BigDecimal.ZERO
            val updatedLots = mutableListOf<Lot>()

            for (lot in position.lots) {
                if (remaining <= BigDecimal.ZERO) {
                    updatedLots += lot
                    continue
                }
                if (lot.quantity <= BigDecimal.ZERO) {
                    continue
                }

                val lotQuantityToClose = if (lot.quantity <= remaining) lot.quantity else remaining
                val lotShare =
                    lot.costBasis.amount
                        .multiply(lotQuantityToClose)
                        .divide(lot.quantity, MATH_CONTEXT)
                costSoldAmount = costSoldAmount + lotShare
                val remainingQuantityInLot = lot.quantity - lotQuantityToClose
                if (remainingQuantityInLot > BigDecimal.ZERO) {
                    val remainingCost = lot.costBasis.amount - lotShare
                    val updatedLot =
                        Lot(
                            remainingQuantityInLot,
                            money(remainingCost, lot.costBasis.ccy),
                        )
                    updatedLots += updatedLot
                }
                remaining -= lotQuantityToClose
            }

            require(remaining.compareTo(BigDecimal.ZERO) == 0) { "Insufficient lots to close quantity" }

            val costSold = money(costSoldAmount, price.ccy)
            val proceeds = price.times(quantity)
            val realized = proceeds - costSold - fees
            val newQuantity = position.quantity - quantity
            val newCostBasis =
                if (newQuantity.compareTo(BigDecimal.ZERO) == 0) {
                    Money.zero(price.ccy)
                } else {
                    money(position.costBasis.amount - costSoldAmount, price.ccy)
                }
            val lotsAfterSale = if (newQuantity.compareTo(BigDecimal.ZERO) == 0) emptyList() else updatedLots
            val updatedPosition = Position(newQuantity, newCostBasis, lotsAfterSale)
            return Result(updatedPosition, realized)
        }
    }

    companion object {
        private val MATH_CONTEXT: MathContext = MathContext.DECIMAL128

        private fun validateInputs(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money,
        ) {
            require(quantity >= BigDecimal.ZERO) { "Quantity cannot be negative" }
            require(fees.ccy == price.ccy) { "Fee currency must match price currency" }
            require(fees.amount >= BigDecimal.ZERO) { "Fees cannot be negative" }
            require(price.ccy == position.costBasis.ccy) { "Currency mismatch with position" }
            require(fees.ccy == position.costBasis.ccy) { "Currency mismatch with position fees" }
        }
    }
}
