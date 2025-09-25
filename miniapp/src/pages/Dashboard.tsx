import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getQuotes, getReport } from "../lib/api";
import type { PortfolioReport, Quote } from "../lib/api";
import { formatMoney, formatPct } from "../lib/format";

export function Dashboard(): JSX.Element {
  const navigate = useNavigate();
  const [report, setReport] = useState<PortfolioReport | null>(null);
  const [quotes, setQuotes] = useState<Quote[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getReport()
      .then((result) => {
        if (!cancelled) {
          setReport(result);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError((err as Error).message);
        }
      });
    getQuotes()
      .then((result) => {
        if (!cancelled) {
          setQuotes(result.slice(0, 3));
        }
      })
      .catch(() => {
        // ignore quote errors to avoid blocking dashboard
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="page">
      <section className="card">
        <h2>Portfolio overview</h2>
        {report ? (
          <div className="metrics">
            <div>
              <span className="metrics__label">Total value</span>
              <span className="metrics__value">{formatMoney(report.summary.totalValue)}</span>
            </div>
            <div>
              <span className="metrics__label">Realized PnL</span>
              <span className="metrics__value">{formatMoney(report.summary.realizedPnl)}</span>
            </div>
            <div>
              <span className="metrics__label">Unrealized PnL</span>
              <span className="metrics__value">{formatMoney(report.summary.unrealizedPnl)}</span>
            </div>
          </div>
        ) : (
          <p>{error ? error : "Loading summary..."}</p>
        )}
        <div className="actions">
          <button type="button" onClick={() => navigate("/positions")}>View positions</button>
          <button type="button" onClick={() => navigate("/reports")}>Go to reports</button>
        </div>
      </section>
      <section className="card">
        <h2>Market snapshot</h2>
        {quotes.length === 0 ? (
          <p>No recent quotes available.</p>
        ) : (
          <ul className="quote-list">
            {quotes.map((quote) => (
              <li key={quote.instrumentId}>
                <span>{quote.instrumentId}</span>
                <span>{formatMoney(quote.price)}</span>
                <span>{formatPct(quote.changePct)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
