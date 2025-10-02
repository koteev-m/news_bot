import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Loading } from "../components/Loading";
import { useToaster } from "../components/Toaster";
import { getQuotes, getReport } from "../lib/api";
import type { PortfolioReport, Quote } from "../lib/api";
import { formatMoney, formatPct } from "../lib/format";

export function Dashboard(): JSX.Element {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const toaster = useToaster();
  const [report, setReport] = useState<PortfolioReport | null>(null);
  const [quotes, setQuotes] = useState<Quote[]>([]);
  const [loadingSummary, setLoadingSummary] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoadingSummary(true);
    getReport()
      .then((result) => {
        if (!cancelled) {
          setReport(result);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          toaster.notifyError((err as Error).message || t("error.generic"));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoadingSummary(false);
        }
      });
    getQuotes()
      .then((result) => {
        if (!cancelled) {
          setQuotes(result.slice(0, 3));
        }
      })
      .catch(() => {
        // quotes are optional, avoid noisy errors
      });
    return () => {
      cancelled = true;
    };
  }, [t, toaster]);

  return (
    <div className="page">
      <section className="card">
        <h2>{t("dashboard.heading")}</h2>
        {loadingSummary ? (
          <Loading variant="skeleton" labelKey="dashboard.loading" />
        ) : report ? (
          <div className="metrics">
            <div>
              <span className="metrics__label">{t("dashboard.metrics.totalValue")}</span>
              <span className="metrics__value">{formatMoney(report.summary.totalValue)}</span>
            </div>
            <div>
              <span className="metrics__label">{t("dashboard.metrics.realizedPnl")}</span>
              <span className="metrics__value">{formatMoney(report.summary.realizedPnl)}</span>
            </div>
            <div>
              <span className="metrics__label">{t("dashboard.metrics.unrealizedPnl")}</span>
              <span className="metrics__value">{formatMoney(report.summary.unrealizedPnl)}</span>
            </div>
          </div>
        ) : (
          <p aria-live="polite">{t("error.generic")}</p>
        )}
        <div className="actions">
          <button type="button" onClick={() => navigate("/positions")}>
            {t("dashboard.actions.viewPositions")}
          </button>
          <button type="button" onClick={() => navigate("/reports")}>
            {t("dashboard.actions.viewReports")}
          </button>
        </div>
      </section>
      <section className="card">
        <h2>{t("dashboard.marketSnapshot")}</h2>
        {quotes.length === 0 ? (
          <p>{t("dashboard.noQuotes")}</p>
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
