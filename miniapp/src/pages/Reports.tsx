import { useState } from "react";
import type { FormEvent } from "react";
import { getReport } from "../lib/api";
import type { PortfolioReport } from "../lib/api";
import { formatDate, formatMoney } from "../lib/format";

export function Reports(): JSX.Element {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [report, setReport] = useState<PortfolioReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const data = await getReport({ from: from || undefined, to: to || undefined });
      setReport(data);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="page">
      <section className="card">
        <h2>Reports</h2>
        <form className="report-form" onSubmit={handleSubmit}>
          <label>
            From
            <input type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
          </label>
          <label>
            To
            <input type="date" value={to} onChange={(event) => setTo(event.target.value)} />
          </label>
          <button type="submit" disabled={loading}>
            {loading ? "Loading..." : "Generate"}
          </button>
        </form>
        {error && <div className="error">{error}</div>}
        {report && (
          <div className="report-summary">
            <h3>Summary</h3>
            <p>Generated at: {formatDate(report.generatedAt)}</p>
            <ul>
              <li>Total value: {formatMoney(report.summary.totalValue)}</li>
              <li>Realized PnL: {formatMoney(report.summary.realizedPnl)}</li>
              <li>Unrealized PnL: {formatMoney(report.summary.unrealizedPnl)}</li>
            </ul>
          </div>
        )}
      </section>
    </div>
  );
}
