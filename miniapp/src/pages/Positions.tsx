import { useCallback, useEffect, useState } from "react";
import { DataTable } from "../components/DataTable";
import { getPositions } from "../lib/api";
import type { Position } from "../lib/api";
import { formatMoney } from "../lib/format";

export function Positions(): JSX.Element {
  const [positions, setPositions] = useState<Position[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    getPositions()
      .then((data) => {
        setPositions(data);
      })
      .catch((err) => {
        setError((err as Error).message);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="page">
      <section className="card">
        <div className="card__header">
          <h2>Positions</h2>
          <button type="button" onClick={load} disabled={loading}>
            {loading ? "Refreshing..." : "Refresh"}
          </button>
        </div>
        {error && <div className="error">{error}</div>}
        <DataTable
          columns={[
            { key: "instrumentId", label: "Instrument" },
            { key: "qty", label: "Quantity" },
            { key: "avgPrice", label: "Avg. Price", render: (row) => formatMoney(row.avgPrice) },
            { key: "lastPrice", label: "Last Price", render: (row) => formatMoney(row.lastPrice) },
            { key: "upl", label: "Unrealized PnL", render: (row) => formatMoney(row.upl) }
          ]}
          rows={positions}
          emptyMessage={loading ? "Loading positions..." : "No positions"}
        />
      </section>
    </div>
  );
}
