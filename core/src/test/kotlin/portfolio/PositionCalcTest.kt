package portfolio

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import portfolio.model.Money
import portfolio.service.PositionCalc
import java.math.BigDecimal
import java.math.MathContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionCalcTest {
    private val averageCalc = PositionCalc.AverageCostCalc()
    private val fifoCalc = PositionCalc.FifoCalc()

    @Test
    fun averageCostScenario() {
        var position = PositionCalc.Position.empty(CURRENCY)

        val firstBuy =
            averageCalc.applyBuy(
                position,
                BigDecimal.TEN,
                money("100"),
                money("5"),
            )
        assertEquals(BigDecimal.TEN, firstBuy.position.quantity)
        assertEquals(money("1005"), firstBuy.position.costBasis)
        assertEquals(money("0"), firstBuy.realizedPnl)

        position = firstBuy.position

        val secondBuy =
            averageCalc.applyBuy(
                position,
                BigDecimal("5"),
                money("110"),
                money("2"),
            )
        assertEquals(BigDecimal("15"), secondBuy.position.quantity)
        assertEquals(money("1557"), secondBuy.position.costBasis)
        assertEquals(money("0"), secondBuy.realizedPnl)
        assertEquals(money("103.8"), secondBuy.position.averagePrice)

        position = secondBuy.position

        val sell =
            averageCalc.applySell(
                position,
                BigDecimal("8"),
                money("120"),
                money("4"),
            )
        assertEquals(BigDecimal("7"), sell.position.quantity)
        assertEquals(money("726.6"), sell.position.costBasis)
        assertEquals(money("125.6"), sell.realizedPnl)
        assertEquals(money("103.8"), sell.position.averagePrice)
    }

    @Test
    fun fifoScenarioWithPartialLots() {
        var position = PositionCalc.Position.empty(CURRENCY)

        val firstBuy =
            fifoCalc.applyBuy(
                position,
                BigDecimal.TEN,
                money("100"),
                money("5"),
            )
        assertEquals(BigDecimal.TEN, firstBuy.position.quantity)
        assertEquals(1, firstBuy.position.lots.size)
        assertEquals(money("1005"), firstBuy.position.costBasis)

        position = firstBuy.position

        val secondBuy =
            fifoCalc.applyBuy(
                position,
                BigDecimal("5"),
                money("110"),
                money("2"),
            )
        assertEquals(BigDecimal("15"), secondBuy.position.quantity)
        assertEquals(2, secondBuy.position.lots.size)
        assertEquals(money("1557"), secondBuy.position.costBasis)

        position = secondBuy.position

        val sell =
            fifoCalc.applySell(
                position,
                BigDecimal("8"),
                money("120"),
                money("4"),
            )
        assertEquals(BigDecimal("7"), sell.position.quantity)
        assertEquals(money("753"), sell.position.costBasis)
        assertEquals(money("152"), sell.realizedPnl)
        assertEquals(2, sell.position.lots.size)
        assertEquals(BigDecimal("2"), sell.position.lots[0].quantity)
        assertEquals(money("201"), sell.position.lots[0].costBasis)
        assertEquals(BigDecimal("5"), sell.position.lots[1].quantity)
        assertEquals(money("552"), sell.position.lots[1].costBasis)
    }

    @Test
    fun fifoCrossLotSellClearsPosition() {
        var position = PositionCalc.Position.empty(CURRENCY)

        position = fifoCalc.applyBuy(position, BigDecimal("3"), money("50"), money("1")).position
        position = fifoCalc.applyBuy(position, BigDecimal("4"), money("55"), money("1.20")).position

        val sell = fifoCalc.applySell(position, BigDecimal("7"), money("60"), money("3"))
        assertEquals(BigDecimal.ZERO, sell.position.quantity)
        assertEquals(money("0"), sell.position.costBasis)
        assertTrue(sell.position.lots.isEmpty())
        assertEquals(money("44.8"), sell.realizedPnl)
    }

    @Test
    fun averagePropertyInvariants() =
        runBlocking {
            checkAll(operationSequencesArb) { specs ->
                val operations = buildOperations(specs)
                verifySequence(averageCalc, operations)
            }
        }

    @Test
    fun fifoPropertyInvariants() =
        runBlocking {
            checkAll(operationSequencesArb) { specs ->
                val operations = buildOperations(specs)
                verifySequence(fifoCalc, operations)
            }
        }

    private fun verifySequence(
        calc: PositionCalc,
        operations: List<Operation>,
    ) {
        var position = PositionCalc.Position.empty(CURRENCY)
        var totalRealized = zeroMoney()

        operations.forEach { operation ->
            val result =
                when (operation) {
                    is Operation.Buy -> calc.applyBuy(position, operation.quantity, operation.price, operation.fee)
                    is Operation.Sell -> calc.applySell(position, operation.quantity, operation.price, operation.fee)
                }
            assertTrue(result.position.quantity >= BigDecimal.ZERO)
            position = result.position
            totalRealized = totalRealized + result.realizedPnl
        }

        if (position.quantity > BigDecimal.ZERO) {
            val expectedAverage =
                Money.of(
                    position.costBasis.amount.divide(position.quantity, MathContext.DECIMAL128),
                    CURRENCY,
                )
            assertNotNull(position.averagePrice)
            assertEquals(expectedAverage, position.averagePrice)
        } else {
            assertEquals(BigDecimal.ZERO, position.quantity)
            assertNull(position.averagePrice)
            assertEquals(zeroMoney(), position.costBasis)
        }

        val totalBuys =
            operations.filterIsInstance<Operation.Buy>().fold(zeroMoney()) { acc, buy ->
                acc + buy.price * buy.quantity + buy.fee
            }
        val totalSellProceeds =
            operations.filterIsInstance<Operation.Sell>().fold(zeroMoney()) { acc, sell ->
                acc + sell.price * sell.quantity
            }
        val totalSellFees =
            operations.filterIsInstance<Operation.Sell>().fold(zeroMoney()) { acc, sell ->
                acc + sell.fee
            }

        assertEquals(BigDecimal.ZERO, position.quantity)
        assertEquals(totalSellProceeds - totalSellFees - totalBuys, totalRealized)
        if (calc is PositionCalc.FifoCalc) {
            assertTrue(position.lots.isEmpty())
        }
    }

    private fun buildOperations(specs: List<OperationSpec>): List<Operation> {
        val operations = mutableListOf<Operation>()
        var openQuantity = BigDecimal.ZERO
        val closingTemplate = specs.lastOrNull()

        specs.forEach { spec ->
            val quantity = BigDecimal(spec.quantity.toLong())
            val price = money(spec.price)
            val fee = money(spec.fee)
            if (spec.isBuy || openQuantity < quantity) {
                operations += Operation.Buy(quantity, price, fee)
                openQuantity += quantity
            } else {
                operations += Operation.Sell(quantity, price, fee)
                openQuantity -= quantity
            }
        }

        if (openQuantity > BigDecimal.ZERO) {
            val price = money(closingTemplate?.price ?: BigDecimal("100"))
            val fee = money(closingTemplate?.fee ?: BigDecimal.ZERO)
            operations += Operation.Sell(openQuantity, price, fee)
        }

        return operations
    }

    private data class OperationSpec(
        val isBuy: Boolean,
        val quantity: Int,
        val price: BigDecimal,
        val fee: BigDecimal,
    )

    private sealed interface Operation {
        data class Buy(
            val quantity: BigDecimal,
            val price: Money,
            val fee: Money,
        ) : Operation

        data class Sell(
            val quantity: BigDecimal,
            val price: Money,
            val fee: Money,
        ) : Operation
    }

    private fun zeroMoney(): Money = money(BigDecimal.ZERO)

    private fun money(amount: String): Money = money(BigDecimal(amount))

    private fun money(amount: BigDecimal): Money = Money.of(amount, CURRENCY)

    private val operationSpecArb =
        Arb.bind(Arb.boolean(), Arb.int(1..10), Arb.int(1000..10000), Arb.int(0..500)) { isBuy, qty, price, fee ->
            OperationSpec(
                isBuy = isBuy,
                quantity = qty,
                price = BigDecimal.valueOf(price.toLong(), 2),
                fee = BigDecimal.valueOf(fee.toLong(), 2),
            )
        }

    private val operationSequencesArb = Arb.list(operationSpecArb, 3..12)

    companion object {
        private const val CURRENCY = "USD"
    }
}
