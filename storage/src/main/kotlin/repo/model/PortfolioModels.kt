package repo.model

import java.time.Instant
import java.util.UUID

/** Row model for the [repo.tables.PortfoliosTable]. */
data class PortfolioEntity(
    val portfolioId: UUID,
    val userId: Long,
    val name: String,
    val baseCurrency: String,
    val isActive: Boolean,
    val createdAt: Instant,
)

/** Payload for creating a new portfolio. */
data class NewPortfolio(
    val userId: Long,
    val name: String,
    val baseCurrency: String,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

/** Payload for updating a portfolio. */
data class PortfolioUpdate(
    val name: String? = null,
    val baseCurrency: String? = null,
    val isActive: Boolean? = null,
)
