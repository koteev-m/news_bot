package repo

import kotlin.reflect.full.declaredMemberFunctions
import kotlin.test.Test
import kotlin.test.assertTrue

class RepositorySignatureTest {
    @Test
    fun `portfolio repository exposes expected API`() {
        val functions = PortfolioRepository::class.declaredMemberFunctions.map { it.name }.toSet()
        assertTrue(functions.containsAll(listOf("create", "findById", "findByUser", "listAll", "searchByName", "update", "delete")))
    }

    @Test
    fun `instrument repository exposes expected API`() {
        val functions = InstrumentRepository::class.declaredMemberFunctions.map { it.name }.toSet()
        assertTrue(
            functions.containsAll(
                listOf(
                    "createInstrument",
                    "updateInstrument",
                    "deleteInstrument",
                    "findById",
                    "findByIsin",
                    "findBySymbol",
                    "search",
                    "listAliases",
                    "addAlias",
                    "removeAlias",
                    "findAlias",
                    "listAll",
                ),
            ),
        )
    }

    @Test
    fun `trade repository exposes expected API`() {
        val functions = TradeRepository::class.declaredMemberFunctions.map { it.name }.toSet()
        assertTrue(
            functions.containsAll(
                listOf(
                    "createTrade",
                    "updateTrade",
                    "deleteTrade",
                    "findById",
                    "findByExternalId",
                    "listByPortfolio",
                    "listByInstrument",
                    "listByPeriod",
                ),
            ),
        )
    }

    @Test
    fun `position repository exposes expected API`() {
        val functions = PositionRepository::class.declaredMemberFunctions.map { it.name }.toSet()
        assertTrue(functions.containsAll(listOf("save", "find", "list", "delete")))
    }

    @Test
    fun `fx rate repository exposes expected API`() {
        val functions = FxRateRepository::class.declaredMemberFunctions.map { it.name }.toSet()
        assertTrue(
            functions.containsAll(
                listOf("upsert", "findOnOrBefore", "findLatest", "find", "list", "delete"),
            ),
        )
        assertTrue(functions.containsAll(listOf("upsert", "findLatest", "find", "list", "delete")))
    }

    @Test
    fun `valuation repository exposes expected API`() {
        val functions = ValuationRepository::class.declaredMemberFunctions.map { it.name }.toSet()
        assertTrue(functions.containsAll(listOf("upsert", "find", "list", "listRange", "delete")))
    }
}
