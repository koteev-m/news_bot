import { useMemo, useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Loading } from "../components/Loading";
import { PortfolioIdField } from "../components/PortfolioIdField";
import { useToaster } from "../components/Toaster";
import { usePortfolioId } from "../hooks/usePortfolioId";
import { getReport } from "../lib/api";
import type { PortfolioReport } from "../lib/api";
import { formatDate, formatMoney } from "../lib/format";

export function Reports(): JSX.Element {
  const { t } = useTranslation();
  const toaster = useToaster();
  const { portfolioId, setPortfolioId, normalizedPortfolioId, isValid } = usePortfolioId();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [report, setReport] = useState<PortfolioReport | null>(null);
  const [loading, setLoading] = useState(false);

  const portfolioIdError = useMemo(() => {
    if (!portfolioId) {
      return t("import.error.portfolioRequired");
    }
    return isValid ? null : t("import.error.portfolioUuid");
  }, [portfolioId, isValid, t]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isValid) {
      return;
    }
    setLoading(true);
    setReport(null);
    try {
      const data = await getReport(normalizedPortfolioId, { from: from || undefined, to: to || undefined });
      setReport(data);
    } catch (err) {
      toaster.notifyError((err as Error).message || t("error.generic"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="page">
      <section className="card">
        <h2>{t("reports.title")}</h2>
        <PortfolioIdField
          id="reports-portfolio-id"
          value={portfolioId}
          onChange={setPortfolioId}
          error={portfolioIdError}
        />
        <form className="report-form" onSubmit={handleSubmit} aria-live="polite">
          <label htmlFor="reports-from">
            {t("reports.form.from")}
            <input id="reports-from" type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
          </label>
          <label htmlFor="reports-to">
            {t("reports.form.to")}
            <input id="reports-to" type="date" value={to} onChange={(event) => setTo(event.target.value)} />
          </label>
          <button type="submit" disabled={loading} aria-busy={loading}>
            {loading ? t("reports.loading") : t("reports.generate")}
          </button>
        </form>
        {loading ? <Loading variant="spinner" labelKey="reports.loading" /> : null}
        {report && !loading ? (
          <div className="report-summary">
            <h3>{t("reports.summaryTitle")}</h3>
            <p>{t("reports.generatedAt", { datetime: formatDate(report.generatedAt) })}</p>
            <ul>
              <li>
                {t("reports.summary.totalValue")}: {formatMoney(report.summary.totalValue)}
              </li>
              <li>
                {t("reports.summary.realizedPnl")}: {formatMoney(report.summary.realizedPnl)}
              </li>
              <li>
                {t("reports.summary.unrealizedPnl")}: {formatMoney(report.summary.unrealizedPnl)}
              </li>
            </ul>
          </div>
        ) : null}
      </section>
    </div>
  );
}
