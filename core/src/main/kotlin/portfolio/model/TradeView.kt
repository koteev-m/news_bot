package portfolio.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class TradeView(
    @Contextual val tradeId: UUID,
    @Contextual val portfolioId: UUID,
    val instrumentId: Long,
    @Contextual val tradeDate: LocalDate,
    val side: TradeSide,
    @Contextual val quantity: BigDecimal,
    val price: Money,
    /**
     * По умолчанию считаем нотацию = price * quantity в валюте цены.
     * Явно передавайте, если нужна другая логика/валюта.
     */
    val notional: Money = price * quantity.abs(),
    val fee: Money = Money.zero(price.ccy),
    val tax: Money? = null,
    val broker: String? = null,
    val note: String? = null,
    val externalId: String? = null,
    @Contextual val executedAt: Instant = tradeDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
)
