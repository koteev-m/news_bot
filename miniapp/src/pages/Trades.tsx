import { useCallback, useEffect, useState } from "react";
import { DataTable } from "../components/DataTable";
import { getTrades } from "../lib/api";
import type { Trade, TradesResponse } from "../lib/api";
import { formatDate, formatMoney } from "../lib/format";

const PAGE_SIZE = 20;

type TradeSide = "buy" | "sell" | "all";

export function Trades(): JSX.Element {
  const [state, setState] = useState<TradesResponse | null>(null);
  const [side, setSide] = useState<TradeSide>("all");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    getTrades({ limit: PAGE_SIZE, offset: page * PAGE_SIZE, side })
      .then((data) => {
        setState(data);
      })
      .catch((err) => {
        setError((err as Error).message);
      })
      .finally(() => setLoading(false));
  }, [page, side]);

  useEffect(() => {
    load();
  }, [load]);

  const totalPages = state ? Math.ceil(state.total / state.limit) : 0;

  return (
    <div className="page">
      <section className="card">
        <div className="card__header">
          <h2>Trades</h2>
          <div className="filters">
            <label>
              Side:
              <select
                value={side}
                onChange={(event) => {
                  setPage(0);
                  setSide(event.target.value as TradeSide);
                }}
              >
                <option value="all">All</option>
                <option value="buy">Buy</option>
                <option value="sell">Sell</option>
              </select>
            </label>
          </div>
        </div>
        {error && <div className="error">{error}</div>}
        <DataTable<Trade>
          columns={[
            { key: "instrumentId", label: "Instrument" },
            { key: "side", label: "Side" },
            { key: "qty", label: "Quantity" },
            { key: "price", label: "Price", render: (row) => formatMoney(row.price) },
            { key: "executedAt", label: "Executed", render: (row) => formatDate(row.executedAt) }
          ]}
          rows={state?.items ?? []}
          emptyMessage={loading ? "Loading trades..." : "No trades"}
        />
        <div className="pagination">
          <button type="button" disabled={page === 0 || loading} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            Previous
          </button>
          <span>
            Page {totalPages === 0 ? 0 : page + 1} / {totalPages}
          </span>
          <button
            type="button"
            disabled={loading || totalPages === 0 || page + 1 >= totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      </section>
    </div>
  );
}
