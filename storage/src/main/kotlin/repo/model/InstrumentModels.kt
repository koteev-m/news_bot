package repo.model

import java.time.Instant

/** Row model for the [repo.tables.InstrumentsTable]. */
data class InstrumentEntity(
    val instrumentId: Long,
    val clazz: String,
    val exchange: String,
    val board: String?,
    val symbol: String,
    val isin: String?,
    val cgId: String?,
    val currency: String,
    val createdAt: Instant,
)

/** Creation payload for instruments. */
data class NewInstrument(
    val clazz: String,
    val exchange: String,
    val board: String?,
    val symbol: String,
    val isin: String?,
    val cgId: String?,
    val currency: String,
    val createdAt: Instant = Instant.now(),
)

/** Update payload for instruments. */
data class InstrumentUpdate(
    val clazz: String? = null,
    val exchange: String? = null,
    val board: String? = null,
    val symbol: String? = null,
    val isin: String? = null,
    val cgId: String? = null,
    val currency: String? = null,
)

/** Row model for the [repo.tables.InstrumentAliasesTable]. */
data class InstrumentAliasEntity(
    val aliasId: Long,
    val instrumentId: Long,
    val alias: String,
    val source: String,
)

/** Creation payload for instrument aliases. */
data class NewInstrumentAlias(
    val instrumentId: Long,
    val alias: String,
    val source: String,
)
