package portfolio

import java.io.Reader
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import portfolio.errors.DomainResult
import portfolio.model.ValuationMethod
import portfolio.service.CsvImportService
import portfolio.service.PortfolioService

class CsvImportServiceTest {
    private val portfolioId: UUID = UUID.randomUUID()

    @Test
    fun `imports sample csv file`() = runBlocking {
        val resolver = FakeInstrumentResolver().apply {
            registerSymbol("MOEX", "TQBR", "SBER", instrumentId = 1L)
            registerAlias("BTCUSDT", "COINGECKO", instrumentId = 2L)
        }
        val lookup = FakeTradeLookup()
        val storage = InMemoryStorage()
        val portfolioService = PortfolioService(storage)
        val service = CsvImportService(resolver, lookup, portfolioService)

        openSampleCsv().use { reader ->
            val result = service.import(portfolioId, reader, ValuationMethod.AVERAGE)
            assertTrue(result.isSuccess)
            val report = result.getOrThrow()
            assertEquals(3, report.inserted)
            assertEquals(0, report.skippedDuplicates)
            assertTrue(report.failed.isEmpty())
        }

        assertEquals(3, storage.recordedTrades.size)
        val aliasTrade = storage.recordedTrades.last()
        assertEquals(2L, aliasTrade.instrumentId)
        assertEquals("CryptoDesk", aliasTrade.broker)
        assertEquals("Crypto accumulation", aliasTrade.note)
    }

    @Test
    fun `skips duplicates by ext id and soft key`() = runBlocking {
        val resolver = FakeInstrumentResolver().apply {
            registerSymbol("MOEX", "TQBR", "SBER", instrumentId = 10L)
        }
        val duplicateKey = CsvImportService.SoftTradeKey.of(
            portfolioId = portfolioId,
            instrumentId = 10L,
            executedAt = Instant.parse("2024-03-16T10:00:00Z"),
            side = portfolio.model.TradeSide.BUY,
            quantity = BigDecimal("3"),
            price = BigDecimal("120"),
        )
        val lookup = FakeTradeLookup(
            existingExtIds = mutableSetOf("db-trade"),
            existingSoftKeys = mutableSetOf(duplicateKey),
        )
        val storage = InMemoryStorage()
        val service = CsvImportService(resolver, lookup, PortfolioService(storage))

        val csv = """
            ext_id,datetime,ticker,exchange,board,alias_source,side,quantity,price,currency,fee,fee_currency,tax,tax_currency,broker,note
            db-trade,2024-03-15T09:00:00Z,SBER,MOEX,TQBR,,BUY,1,100,RUB,0,RUB,0,RUB,,
            new-ext,2024-03-16T10:00:00Z,SBER,MOEX,TQBR,,BUY,2,150,RUB,0,RUB,0,RUB,,
            new-ext,2024-03-16T10:00:00Z,SBER,MOEX,TQBR,,BUY,2,150,RUB,0,RUB,0,RUB,,
            ,2024-03-16T10:00:00Z,SBER,MOEX,TQBR,,BUY,2,150,RUB,0,RUB,0,RUB,,
            ,2024-03-16T10:00:00Z,SBER,MOEX,TQBR,,BUY,3,120,RUB,0,RUB,0,RUB,,
        """.trimIndent()

        val result = service.import(portfolioId, StringReader(csv), ValuationMethod.AVERAGE)
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(1, report.inserted)
        assertEquals(4, report.skippedDuplicates)
        assertTrue(report.failed.isEmpty())
        assertEquals(1, storage.recordedTrades.size)
    }

    @Test
    fun `records failures but continues import`() = runBlocking {
        val resolver = FakeInstrumentResolver().apply {
            registerSymbol("MOEX", "TQBR", "SBER", instrumentId = 5L)
        }
        val lookup = FakeTradeLookup()
        val storage = InMemoryStorage()
        val service = CsvImportService(resolver, lookup, PortfolioService(storage))

        val csv = """
            ext_id,datetime,ticker,exchange,board,alias_source,side,quantity,price,currency,fee,fee_currency,tax,tax_currency,broker,note
            buy-1,2024-03-18T10:00:00Z,SBER,MOEX,TQBR,,BUY,5,100,RUB,0,RUB,0,RUB,,
            sell-too-much,2024-03-19T10:00:00Z,SBER,MOEX,TQBR,,SELL,10,110,RUB,0,RUB,0,RUB,,
            ,2024-03-20T10:00:00Z,SBER,MOEX,TQBR,,SELL,5,120,RUB,0,RUB,0,RUB,,
        """.trimIndent()

        val result = service.import(portfolioId, StringReader(csv), ValuationMethod.AVERAGE)
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(2, report.inserted)
        assertEquals(0, report.skippedDuplicates)
        assertEquals(1, report.failed.size)
        val failure = report.failed.single()
        assertEquals("sell-too-much", failure.extId)
        assertTrue(failure.message.contains("Cannot sell more than current quantity"))
        assertEquals(2, storage.recordedTrades.size)
    }

    @Test
    fun `resolves instruments through aliases`() = runBlocking {
        val resolver = FakeInstrumentResolver().apply {
            registerAlias("SBERP", "NEWS", instrumentId = 42L)
        }
        val lookup = FakeTradeLookup()
        val storage = InMemoryStorage()
        val service = CsvImportService(resolver, lookup, PortfolioService(storage))

        val csv = """
            ext_id,datetime,ticker,exchange,board,alias_source,side,quantity,price,currency,fee,fee_currency,tax,tax_currency,broker,note
            alias-1,2024-03-21T08:30:00Z,SBERP,,,NEWS,BUY,1,200,RUB,0,RUB,0,RUB,,Alias import
            alias-2,2024-03-22T08:30:00Z,UNKNOWN,,,,BUY,1,200,RUB,0,RUB,0,RUB,,Missing mapping
        """.trimIndent()

        val result = service.import(portfolioId, StringReader(csv), ValuationMethod.FIFO)
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(1, report.inserted)
        assertEquals(0, report.skippedDuplicates)
        assertEquals(1, report.failed.size)
        val failureMessage = report.failed.first().message
        assertTrue(
            failureMessage.contains("Either exchange or alias_source"),
            "Unexpected failure message: $failureMessage",
        )

        assertEquals(1, storage.recordedTrades.size)
        val recorded = storage.recordedTrades.first()
        assertEquals(42L, recorded.instrumentId)
        assertEquals("Alias import", recorded.note)
    }

    private class InMemoryStorage : PortfolioService.Storage {
        private val positions = mutableMapOf<Triple<UUID, Long, ValuationMethod>, PortfolioService.StoredPosition>()
        val recordedTrades = mutableListOf<PortfolioService.StoredTrade>()

        override suspend fun <T> transaction(
            block: suspend PortfolioService.Storage.Transaction.() -> DomainResult<T>,
        ): DomainResult<T> {
            val tx = object : PortfolioService.Storage.Transaction {
                override suspend fun loadPosition(
                    portfolioId: UUID,
                    instrumentId: Long,
                    method: ValuationMethod,
                ): DomainResult<PortfolioService.StoredPosition?> {
                    val key = Triple(portfolioId, instrumentId, method)
                    return DomainResult.success(positions[key])
                }

                override suspend fun savePosition(
                    position: PortfolioService.StoredPosition,
                ): DomainResult<Unit> {
                    val key = Triple(position.portfolioId, position.instrumentId, position.valuationMethod)
                    positions[key] = position
                    return DomainResult.success(Unit)
                }

                override suspend fun recordTrade(
                    trade: PortfolioService.StoredTrade,
                ): DomainResult<Unit> {
                    recordedTrades += trade
                    return DomainResult.success(Unit)
                }
            }

            return tx.block()
        }
    }

    private class FakeInstrumentResolver : CsvImportService.InstrumentResolver {
        private val bySymbol = mutableMapOf<SymbolKey, Long>()
        private val byAlias = mutableMapOf<AliasKey, Long>()

        override suspend fun findBySymbol(
            exchange: String,
            board: String?,
            symbol: String,
        ): DomainResult<CsvImportService.InstrumentRef?> {
            val key = SymbolKey(exchange.uppercase(Locale.ROOT), board?.uppercase(Locale.ROOT), symbol.uppercase(Locale.ROOT))
            val instrumentId = bySymbol[key] ?: return DomainResult.success(null)
            return DomainResult.success(CsvImportService.InstrumentRef(instrumentId))
        }

        override suspend fun findByAlias(
            alias: String,
            source: String,
        ): DomainResult<CsvImportService.InstrumentRef?> {
            val key = AliasKey(alias.uppercase(Locale.ROOT), source.uppercase(Locale.ROOT))
            val instrumentId = byAlias[key] ?: return DomainResult.success(null)
            return DomainResult.success(CsvImportService.InstrumentRef(instrumentId))
        }

        fun registerSymbol(exchange: String, board: String?, symbol: String, instrumentId: Long) {
            bySymbol[SymbolKey(exchange.uppercase(Locale.ROOT), board?.uppercase(Locale.ROOT), symbol.uppercase(Locale.ROOT))] = instrumentId
        }

        fun registerAlias(alias: String, source: String, instrumentId: Long) {
            byAlias[AliasKey(alias.uppercase(Locale.ROOT), source.uppercase(Locale.ROOT))] = instrumentId
        }

        private data class SymbolKey(val exchange: String, val board: String?, val symbol: String)
        private data class AliasKey(val alias: String, val source: String)
    }

    private class FakeTradeLookup(
        private val existingExtIds: MutableSet<String> = mutableSetOf(),
        private val existingSoftKeys: MutableSet<CsvImportService.SoftTradeKey> = mutableSetOf(),
    ) : CsvImportService.TradeLookup {
        override suspend fun existsByExternalId(portfolioId: UUID, externalId: String): DomainResult<Boolean> {
            return DomainResult.success(existingExtIds.contains(externalId))
        }

        override suspend fun existsBySoftKey(key: CsvImportService.SoftTradeKey): DomainResult<Boolean> {
            return DomainResult.success(existingSoftKeys.contains(key))
        }
    }

    private fun openSampleCsv(): Reader {
        val candidates = listOf(
            Paths.get("tests", "resources", "trades.csv"),
            Paths.get("..", "tests", "resources", "trades.csv"),
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Sample trades CSV not found")
        return Files.newBufferedReader(path)
    }
}
