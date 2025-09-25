package alerts.ports

import alerts.config.InstrumentClass

public data class MarketSnapshot(
    val classId: InstrumentClass,
    val pctChange: Double,
    val volumeMult: Double
)

public interface MarketDataPort {
    public suspend fun fastWindow(instrumentId: Long): MarketSnapshot
    public suspend fun dayWindow(instrumentId: Long): MarketSnapshot
    public suspend fun atr14(instrumentId: Long): Double?
    public suspend fun sigma30d(instrumentId: Long): Double?
}

public interface PortfolioPort {
    public suspend fun dayChangePct(portfolioId: java.util.UUID): Double
    public suspend fun drawdownPct(portfolioId: java.util.UUID): Double
}

public interface NotifierPort {
    public suspend fun push(instrumentId: Long, event: alerts.model.AlertEvent)
    public suspend fun pushPortfolio(portfolioId: java.util.UUID, event: alerts.model.PortfolioAlertEvent)
}
