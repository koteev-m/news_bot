import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { DataTable } from "../components/DataTable";
import { Loading } from "../components/Loading";
import { useToaster } from "../components/Toaster";
import { getPositions } from "../lib/api";
import type { Position } from "../lib/api";
import { formatMoney } from "../lib/format";

export function Positions(): JSX.Element {
  const { t } = useTranslation();
  const toaster = useToaster();
  const [positions, setPositions] = useState<Position[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    getPositions()
      .then((data) => {
        setPositions(data);
      })
      .catch((err) => {
        toaster.notifyError((err as Error).message || t("error.generic"));
      })
      .finally(() => {
        setLoading(false);
      });
  }, [t, toaster]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="page">
      <section className="card">
        <div className="card__header">
          <h2>{t("positions.title")}</h2>
          <button type="button" onClick={load} disabled={loading} aria-busy={loading}>
            {loading ? t("positions.refreshing") : t("positions.refresh")}
          </button>
        </div>
        {loading && positions.length === 0 ? <Loading variant="skeleton" labelKey="positions.loading" /> : null}
        <DataTable
          columns={[
            { key: "instrumentId", label: t("positions.columns.instrument") },
            { key: "qty", label: t("positions.columns.quantity") },
            { key: "avgPrice", label: t("positions.columns.avgPrice"), render: (row) => formatMoney(row.avgPrice) },
            { key: "lastPrice", label: t("positions.columns.lastPrice"), render: (row) => formatMoney(row.lastPrice) },
            { key: "upl", label: t("positions.columns.upl"), render: (row) => formatMoney(row.upl) },
          ]}
          rows={positions}
          emptyMessage={loading ? t("positions.loading") : t("positions.empty")}
        />
      </section>
    </div>
  );
}
