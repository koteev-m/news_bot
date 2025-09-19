package portfolio.service

import java.math.BigDecimal
import java.math.MathContext
import portfolio.model.Money

interface PositionCalc {
    data class Lot(
        val quantity: BigDecimal,
        val costBasis: Money
    ) {
        init {
            require(quantity >= BigDecimal.ZERO) { "Lot quantity cannot be negative" }
        }

        val averagePrice: Money?
            get() = if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                null
            } else {
                Money.of(costBasis.amount.divide(quantity, MATH_CONTEXT), costBasis.currency)
            }
    }

    data class Position(
        val quantity: BigDecimal,
        val costBasis: Money,
        val lots: List<Lot> = emptyList()
    ) {
        init {
            require(quantity >= BigDecimal.ZERO) { "Position quantity cannot be negative" }
            require(lots.all { it.costBasis.currency == costBasis.currency }) {
                "Lot currency must match position currency"
            }
        }

        val currency: String = costBasis.currency

        val averagePrice: Money?
            get() = if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                null
            } else {
                Money.of(costBasis.amount.divide(quantity, MATH_CONTEXT), currency)
            }

        companion object {
            fun empty(currency: String): Position = Position(
                BigDecimal.ZERO,
                Money.of(BigDecimal.ZERO, currency),
                emptyList()
            )
        }
    }

    data class Result(
        val position: Position,
        val realizedPnl: Money
    )

    fun applyBuy(
        position: Position,
        quantity: BigDecimal,
        price: Money,
        fees: Money = Money.of(BigDecimal.ZERO, price.currency)
    ): Result

    fun applySell(
        position: Position,
        quantity: BigDecimal,
        price: Money,
        fees: Money = Money.of(BigDecimal.ZERO, price.currency)
    ): Result

    class AverageCostCalc : PositionCalc {
        override fun applyBuy(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money
        ): Result {
            validateInputs(position, quantity, price, fees)
            require(quantity > BigDecimal.ZERO) { "Buy quantity must be positive" }

            val totalCost = price * quantity + fees
            val newQuantity = position.quantity + quantity
            val newCostBasis = position.costBasis + totalCost
            val updatedPosition = Position(newQuantity, newCostBasis)
            return Result(updatedPosition, zero(price.currency))
        }

        override fun applySell(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money
        ): Result {
            validateInputs(position, quantity, price, fees)
            require(quantity > BigDecimal.ZERO) { "Sell quantity must be positive" }
            require(position.quantity >= quantity) { "Cannot sell more than current quantity" }

            val proceeds = price * quantity
            val costSoldAmount = position.costBasis.amount.multiply(quantity).divide(position.quantity, MATH_CONTEXT)
            val costSold = Money.of(costSoldAmount, price.currency)
            val newQuantity = position.quantity - quantity
            val newCostBasis = if (newQuantity.compareTo(BigDecimal.ZERO) == 0) {
                zero(price.currency)
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
            fees: Money
        ): Result {
            validateInputs(position, quantity, price, fees)
            require(quantity > BigDecimal.ZERO) { "Buy quantity must be positive" }

            val totalCost = price * quantity + fees
            val newLot = Lot(quantity, totalCost)
            val newQuantity = position.quantity + quantity
            val newCostBasis = position.costBasis + totalCost
            val updatedLots = position.lots + newLot
            val updatedPosition = Position(newQuantity, newCostBasis, updatedLots)
            return Result(updatedPosition, zero(price.currency))
        }

        override fun applySell(
            position: Position,
            quantity: BigDecimal,
            price: Money,
            fees: Money
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
                val lotShare = lot.costBasis.amount.multiply(lotQuantityToClose).divide(lot.quantity, MATH_CONTEXT)
                costSoldAmount = costSoldAmount + lotShare
                val remainingQuantityInLot = lot.quantity - lotQuantityToClose
                if (remainingQuantityInLot > BigDecimal.ZERO) {
                    val remainingCost = lot.costBasis.amount - lotShare
                    val updatedLot = Lot(
                        remainingQuantityInLot,
                        Money.of(remainingCost, lot.costBasis.currency)
                    )
                    updatedLots += updatedLot
                }
                remaining -= lotQuantityToClose
            }

            require(remaining.compareTo(BigDecimal.ZERO) == 0) { "Insufficient lots to close quantity" }

            val costSold = Money.of(costSoldAmount, price.currency)
            val proceeds = price * quantity
            val realized = proceeds - costSold - fees
            val newQuantity = position.quantity - quantity
            val newCostBasis = if (newQuantity.compareTo(BigDecimal.ZERO) == 0) {
                zero(price.currency)
            } else {
                Money.of(position.costBasis.amount - costSoldAmount, price.currency)
            }
            val lotsAfterSale = if (newQuantity.compareTo(BigDecimal.ZERO) == 0) emptyList() else updatedLots
            val updatedPosition = Position(newQuantity, newCostBasis, lotsAfterSale)
            return Result(updatedPosition, realized)
        }
    }

    companion object {
        private val MATH_CONTEXT: MathContext = MathContext.DECIMAL128

        private fun validateInputs(position: Position, quantity: BigDecimal, price: Money, fees: Money) {
            require(quantity >= BigDecimal.ZERO) { "Quantity cannot be negative" }
            require(fees.currency == price.currency) { "Fee currency must match price currency" }
            require(fees.amount >= BigDecimal.ZERO) { "Fees cannot be negative" }
            require(price.currency == position.costBasis.currency) { "Currency mismatch with position" }
            require(fees.currency == position.costBasis.currency) { "Currency mismatch with position fees" }
        }

        private fun zero(currency: String): Money = Money.of(BigDecimal.ZERO, currency)
    }
}
