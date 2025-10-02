import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { DataTable } from "../components/DataTable";
import { Loading } from "../components/Loading";
import { useToaster } from "../components/Toaster";
import { getTrades } from "../lib/api";
import type { Trade, TradesResponse } from "../lib/api";
import { formatDate, formatMoney } from "../lib/format";

const PAGE_SIZE = 20;

type TradeSide = "buy" | "sell" | "all";

export function Trades(): JSX.Element {
  const { t } = useTranslation();
  const toaster = useToaster();
  const [state, setState] = useState<TradesResponse | null>(null);
  const [side, setSide] = useState<TradeSide>("all");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    getTrades({ limit: PAGE_SIZE, offset: page * PAGE_SIZE, side })
      .then((data) => {
        setState(data);
      })
      .catch((err) => {
        toaster.notifyError((err as Error).message || t("error.generic"));
      })
      .finally(() => setLoading(false));
  }, [page, side, t, toaster]);

  useEffect(() => {
    load();
  }, [load]);

  const totalPages = useMemo(() => (state ? Math.ceil(state.total / state.limit) : 0), [state]);

  return (
    <div className="page">
      <section className="card">
        <div className="card__header">
          <h2>{t("trades.title")}</h2>
          <div className="filters">
            <label htmlFor="trades-side-select">
              {t("trades.filter.side")}:
              <select
                id="trades-side-select"
                value={side}
                onChange={(event) => {
                  setPage(0);
                  setSide(event.target.value as TradeSide);
                }}
              >
                <option value="all">{t("trades.filter.all")}</option>
                <option value="buy">{t("trades.filter.buy")}</option>
                <option value="sell">{t("trades.filter.sell")}</option>
              </select>
            </label>
          </div>
        </div>
        {loading && !state ? <Loading variant="skeleton" labelKey="trades.loading" /> : null}
        <DataTable<Trade>
          columns={[
            { key: "instrumentId", label: t("trades.columns.instrument") },
            { key: "side", label: t("trades.columns.side") },
            { key: "qty", label: t("trades.columns.quantity") },
            { key: "price", label: t("trades.columns.price"), render: (row) => formatMoney(row.price) },
            { key: "executedAt", label: t("trades.columns.executed"), render: (row) => formatDate(row.executedAt) },
          ]}
          rows={state?.items ?? []}
          emptyMessage={loading ? t("trades.loading") : t("trades.empty")}
        />
        <div className="pagination" role="navigation" aria-label={t("trades.pagination.page", { current: page + 1, total: totalPages })}>
          <button type="button" disabled={page === 0 || loading} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            {t("trades.pagination.previous")}
          </button>
          <span>{t("trades.pagination.page", { current: totalPages === 0 ? 0 : page + 1, total: totalPages })}</span>
          <button
            type="button"
            disabled={loading || totalPages === 0 || page + 1 >= totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            {t("trades.pagination.next")}
          </button>
        </div>
      </section>
    </div>
  );
}
